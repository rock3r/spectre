package dev.sebastiano.spectre.core

import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Unit-level tests for [ComposeAutomator.waitUntil], the predicate-shaped wait scoped to the
 * Spectre-observable semantics tree.
 *
 * Mirrors `WaitUntilGoneTest`: the public-boundary contracts (argument validation, EDT rejection,
 * the receiver the predicate is handed, immediate return) run against a headless automator, and the
 * polling loop itself is exercised through [waitUntilInternal] on virtual time — the same seam
 * `waitForIdleInternal` and `waitUntilGoneInternal` use — so the timeout diagnostics are pinned
 * without a live Compose tree. The end-to-end paths that need real Compose live in the
 * sample-desktop validation suite (`WaitUntilValidationTest`).
 */
class WaitUntilTest {

    @Test
    fun `waitUntil requires a non-blank description`() = runBlocking {
        val automator = headlessAutomator()
        val error =
            assertFailsWith<IllegalArgumentException> {
                automator.waitUntil(description = "   ") { true }
            }
        assertEquals("description must not be blank", error.message)
    }

    @Test
    fun `waitUntil returns at once when the condition already holds`() = runBlocking {
        // With window discovery off the tracked set is empty, so a condition phrased against it
        // holds on the first poll: the wait must return instead of sitting out the timeout.
        val automator =
            ComposeAutomator.inProcess(
                robotDriver = RobotDriver.headless(),
                discoverWindows = false,
            )
        val started = TimeSource.Monotonic.markNow()
        automator.waitUntil(description = "no window is tracked", timeout = 10.seconds) {
            windows().isEmpty()
        }
        assertTrue(
            started.elapsedNow() < 5.seconds,
            "waitUntil should return as soon as the condition holds, took ${started.elapsedNow()}",
        )
    }

    @Test
    fun `waitUntil hands each poll a freshly read tree`() = runBlocking {
        // The condition is scoped to the semantics tree, and the tree it sees must be current:
        // every poll goes through tree(), which refreshes windows before reading nodes.
        val automator =
            ComposeAutomator.inProcess(
                robotDriver = RobotDriver.headless(),
                discoverWindows = false,
            )
        val seen = mutableListOf<AutomatorTree>()

        automator.waitUntil(
            description = "the third poll",
            timeout = 10.seconds,
            pollInterval = 1.milliseconds,
        ) {
            seen += this
            seen.size == 3
        }

        assertEquals(3, seen.size)
        assertTrue(
            seen.distinct().size == 3,
            "each poll must observe its own tree snapshot, got ${seen.size} polls over " +
                "${seen.distinct().size} distinct trees",
        )
    }

    @Test
    fun `waitUntil names the description and the timeout when the condition never holds`() =
        runBlocking {
            val automator = headlessAutomator()
            val error =
                assertFailsWith<IllegalStateException> {
                    automator.waitUntil(
                        description = "the settings dialog is showing",
                        timeout = 100.milliseconds,
                        pollInterval = 10.milliseconds,
                    ) {
                        false
                    }
                }

            val message = error.message.orEmpty()
            assertTrue(
                message.contains("waitUntil timed out after 100ms"),
                "expected the timeout in the message, got: $message",
            )
            assertTrue(
                message.contains("the settings dialog is showing"),
                "expected the description in the message, got: $message",
            )
        }

    @Test
    fun `waitUntil reports the bad-argument error when called from the EDT with a blank description`() {
        // Argument validation must run BEFORE the EDT check, mirroring waitForNode and
        // waitUntilGone: the bad input is the more actionable diagnostic for a caller doing
        // two things wrong.
        assumeLiveAwtAvailable()
        val automator = headlessAutomator()
        val errorRef = AtomicReference<Throwable?>()
        SwingUtilities.invokeAndWait {
            runBlocking {
                errorRef.set(
                    runCatching { automator.waitUntil(description = "") { true } }.exceptionOrNull()
                )
            }
        }
        val error = errorRef.get()
        assertTrue(
            error is IllegalArgumentException,
            "expected IllegalArgumentException, got $error",
        )
        assertEquals("description must not be blank", error.message)
    }

    @Test
    fun `waitUntil rejects EDT callers with valid arguments`() {
        assumeLiveAwtAvailable()
        val automator = headlessAutomator()
        val errorRef = AtomicReference<Throwable?>()
        SwingUtilities.invokeAndWait {
            runBlocking {
                errorRef.set(
                    runCatching { automator.waitUntil(description = "anything") { true } }
                        .exceptionOrNull()
                )
            }
        }
        val error = errorRef.get()
        assertTrue(error is IllegalStateException, "expected IllegalStateException, got $error")
        assertTrue(
            error.message?.contains(
                "waitUntil must not be called from the AWT event dispatch thread"
            ) == true,
            "expected curated EDT message, got: ${error.message}",
        )
    }

    @Test
    fun `waitUntilInternal returns as soon as the condition holds`() = runTest {
        val clock = FakeClock()
        val holdsPerPoll = ArrayDeque(listOf(false, false, true))
        var polls = 0

        waitUntilInternal(
            timeout = 1.seconds,
            pollInterval = 100.milliseconds,
            description = "the popup is showing",
            condition = {
                polls++
                holdsPerPoll.removeFirst()
            },
            clock = clock,
            sleep = clock::advance,
        )

        assertEquals(3, polls, "should stop polling on the first satisfied observation")
    }

    @Test
    fun `waitUntilInternal fails naming the description and the timeout`() = runTest {
        val clock = FakeClock()
        var polls = 0

        // A bare withTimeout cancellation would surface as TimeoutCancellationException (a
        // CancellationException) and say nothing about what was awaited; the contract is a plain
        // IllegalStateException carrying the caller's own description.
        val error =
            assertFailsWith<IllegalStateException> {
                waitUntilInternal(
                    timeout = 500.milliseconds,
                    pollInterval = 100.milliseconds,
                    description = "the popup is showing",
                    condition = {
                        polls++
                        false
                    },
                    clock = clock,
                    sleep = clock::advance,
                )
            }

        val message = error.message.orEmpty()
        assertTrue(
            message.contains("waitUntil timed out after 500ms"),
            "expected the timeout in the message, got: $message",
        )
        assertTrue(
            message.contains("the popup is showing"),
            "expected the description in the message, got: $message",
        )
        // Polls at t=0,100,...,500: the loop keeps observing until the deadline and takes one
        // final observation at it, mirroring waitUntilGoneInternal.
        assertEquals(6, polls)
    }

    @Test
    fun `waitUntilInternal observes the condition once even with a zero timeout`() = runTest {
        val clock = FakeClock()
        var polls = 0

        waitUntilInternal(
            timeout = Duration.ZERO,
            pollInterval = 100.milliseconds,
            description = "the popup is showing",
            condition = {
                polls++
                true
            },
            clock = clock,
            sleep = clock::advance,
        )

        assertEquals(1, polls, "a zero timeout must still take one observation")
    }

    @Test
    fun `waitUntilInternal clamps the poll sleep to the remaining timeout`() = runTest {
        val clock = FakeClock()
        val sleeps = mutableListOf<Duration>()
        var polls = 0

        assertFailsWith<IllegalStateException> {
            waitUntilInternal(
                timeout = 50.milliseconds,
                pollInterval = 1.seconds,
                description = "the popup is showing",
                condition = {
                    polls++
                    false
                },
                clock = clock,
                sleep = {
                    sleeps += it
                    clock.advance(it)
                },
            )
        }

        assertEquals(
            listOf(50.milliseconds),
            sleeps,
            "the only sleep must be clamped to the deadline",
        )
        assertEquals(50L, clock.now(), "the wait must fail at the deadline, not an interval later")
        assertEquals(2, polls)
    }

    @Test
    fun `waitUntilInternal observes a late condition at the deadline, not an interval later`() =
        runTest {
            val clock = FakeClock()
            val holdsPerPoll = ArrayDeque(listOf(false, true))

            waitUntilInternal(
                timeout = 50.milliseconds,
                pollInterval = 1.seconds,
                description = "the popup is showing",
                condition = { holdsPerPoll.removeFirst() },
                clock = clock,
                sleep = clock::advance,
            )

            assertEquals(50L, clock.now(), "the second poll must land on the deadline")
        }

    @Test
    fun `waitUntilInternal accepts a condition observed true after the sleep overshoots`() =
        runTest {
            // Characterisation test, not a new contract: this pins behaviour the shared loop has
            // had since waitUntilGone shipped, so it stays a deliberate choice rather than an
            // accident (Codex on #489). A real delay() can resume past the deadline under
            // scheduler load. When the condition is true at that observation the wait returns
            // instead of throwing, for two reasons: reporting "never held" about a condition just
            // observed to hold would be a false diagnostic, and failing a wait whose condition did
            // settle manufactures flakiness on loaded machines. The strict alternative — reject
            // any observation past the deadline — is a semantics change to waitUntilGone as well,
            // so it is not this PR's to make.
            val clock = FakeClock()
            val holdsPerPoll = ArrayDeque(listOf(false, true))

            waitUntilInternal(
                timeout = 50.milliseconds,
                pollInterval = 1.seconds,
                description = "the popup is showing",
                condition = { holdsPerPoll.removeFirst() },
                clock = clock,
                sleep = { clock.advance(it + 20.milliseconds) },
            )

            assertTrue(
                clock.now() > 50,
                "the accepted observation must land past the deadline, got ${clock.now()}",
            )
        }

    private fun headlessAutomator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}

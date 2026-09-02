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
 * Unit-level tests for [ComposeAutomator.waitUntilGone] (#438), the wait-for-absence counterpart to
 * [ComposeAutomator.waitForNode].
 *
 * The public-boundary contracts (argument validation, EDT rejection, immediate return on an empty
 * tracked-window set) run against a headless automator. The polling loop itself is exercised
 * through [waitUntilGoneInternal] on virtual time, the same seam `waitForIdleInternal` uses, so the
 * timeout diagnostics are pinned without a live Compose tree. The end-to-end paths that need real
 * Compose — a dismissed popup, a closed secondary `Window`, a node that stays on screen — live in
 * the sample-desktop validation suite (`WaitUntilGoneValidationTest`).
 */
class WaitUntilGoneTest {

    @Test
    fun `waitUntilGone requires tag or text`() = runBlocking {
        val automator = headlessAutomator()
        val error =
            assertFailsWith<IllegalArgumentException> {
                automator.waitUntilGone(tag = null, text = null)
            }
        assertEquals("Either tag or text must be specified", error.message)
    }

    @Test
    fun `waitUntilGone returns at once when no tracked window holds a matching node`() =
        runBlocking {
            // With window discovery off the tracked set is empty, so nothing can match: the
            // wait must return on its first poll instead of sitting out the timeout.
            val automator =
                ComposeAutomator.inProcess(
                    robotDriver = RobotDriver.headless(),
                    discoverWindows = false,
                )
            val started = TimeSource.Monotonic.markNow()
            automator.waitUntilGone(tag = "never-present", timeout = 10.seconds)
            assertTrue(
                started.elapsedNow() < 5.seconds,
                "waitUntilGone should return as soon as the selector matches nothing, " +
                    "took ${started.elapsedNow()}",
            )
        }

    @Test
    fun `waitUntilGone reports the bad-argument error when called from the EDT with null inputs`() {
        // Argument validation must run BEFORE the EDT check, mirroring waitForNode: the bad
        // input is the more actionable diagnostic for a caller doing two things wrong.
        assumeLiveAwtAvailable()
        val automator = headlessAutomator()
        val errorRef = AtomicReference<Throwable?>()
        SwingUtilities.invokeAndWait {
            runBlocking {
                errorRef.set(runCatching { automator.waitUntilGone() }.exceptionOrNull())
            }
        }
        val error = errorRef.get()
        assertTrue(
            error is IllegalArgumentException,
            "expected IllegalArgumentException, got $error",
        )
        assertEquals("Either tag or text must be specified", error.message)
    }

    @Test
    fun `waitUntilGone rejects EDT callers with valid arguments`() {
        assumeLiveAwtAvailable()
        val automator = headlessAutomator()
        val errorRef = AtomicReference<Throwable?>()
        SwingUtilities.invokeAndWait {
            runBlocking {
                errorRef.set(
                    runCatching { automator.waitUntilGone(tag = "irrelevant") }.exceptionOrNull()
                )
            }
        }
        val error = errorRef.get()
        assertTrue(error is IllegalStateException, "expected IllegalStateException, got $error")
        assertTrue(
            error.message?.contains(
                "waitUntilGone must not be called from the AWT event dispatch thread"
            ) == true,
            "expected curated EDT message, got: ${error.message}",
        )
    }

    @Test
    fun `waitUntilGoneInternal returns as soon as the selector matches nothing`() = runTest {
        val clock = FakeClock()
        val presentPerPoll = ArrayDeque(listOf(2, 1, 0))
        var polls = 0

        waitUntilGoneInternal(
            timeout = 1.seconds,
            pollInterval = 100.milliseconds,
            selector = "tag=\"popup.body\"",
            countPresent = {
                polls++
                presentPerPoll.removeFirst()
            },
            clock = clock,
            sleep = clock::advance,
        )

        assertEquals(3, polls, "should stop polling on the first empty observation")
    }

    @Test
    fun `waitUntilGoneInternal fails naming the selector, the timeout and what stayed on screen`() =
        runTest {
            val clock = FakeClock()
            var polls = 0

            // A bare withTimeout cancellation would surface as TimeoutCancellationException
            // (a CancellationException) and say nothing about the selector; the contract is a
            // plain IllegalStateException carrying the diagnostics.
            val error =
                assertFailsWith<IllegalStateException> {
                    waitUntilGoneInternal(
                        timeout = 500.milliseconds,
                        pollInterval = 100.milliseconds,
                        selector = "tag=\"popup.body\"",
                        countPresent = {
                            polls++
                            2
                        },
                        clock = clock,
                        sleep = clock::advance,
                    )
                }

            val message = error.message.orEmpty()
            assertTrue(
                message.contains("waitUntilGone timed out after 500ms"),
                "expected the timeout in the message, got: $message",
            )
            assertTrue(
                message.contains("tag=\"popup.body\""),
                "expected the selector in the message, got: $message",
            )
            assertTrue(
                message.contains("2 node(s)"),
                "expected the still-present count in the message, got: $message",
            )
            // Polls at t=0,100,...,500: the loop keeps observing until the deadline and takes
            // one final observation at it, so the reported count is the last thing seen.
            assertEquals(6, polls)
        }

    @Test
    fun `waitUntilGoneInternal observes the selector once even with a zero timeout`() = runTest {
        val clock = FakeClock()
        var polls = 0

        waitUntilGoneInternal(
            timeout = Duration.ZERO,
            pollInterval = 100.milliseconds,
            selector = "text=\"Dismiss\"",
            countPresent = {
                polls++
                0
            },
            clock = clock,
            sleep = clock::advance,
        )

        assertEquals(1, polls, "a zero timeout must still take one observation")
    }

    @Test
    fun `waitUntilGoneInternal clamps the poll sleep to the remaining timeout`() = runTest {
        // Codex on #482: with pollInterval > remaining time, an unclamped sleep would carry the
        // wait a full interval past the deadline before it could fail.
        val clock = FakeClock()
        val sleeps = mutableListOf<Duration>()
        var polls = 0

        assertFailsWith<IllegalStateException> {
            waitUntilGoneInternal(
                timeout = 50.milliseconds,
                pollInterval = 1.seconds,
                selector = "tag=\"popup.body\"",
                countPresent = {
                    polls++
                    1
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
    fun `waitUntilGoneInternal observes a late disappearance at the deadline, not an interval later`() =
        runTest {
            val clock = FakeClock()
            val presentPerPoll = ArrayDeque(listOf(1, 0))

            waitUntilGoneInternal(
                timeout = 50.milliseconds,
                pollInterval = 1.seconds,
                selector = "tag=\"popup.body\"",
                countPresent = { presentPerPoll.removeFirst() },
                clock = clock,
                sleep = clock::advance,
            )

            assertEquals(50L, clock.now(), "the second poll must land on the deadline")
        }

    @Test
    fun `describeNodeSelector names every non-null criterion`() {
        assertEquals("tag=\"a\"", describeNodeSelector(tag = "a", text = null))
        assertEquals("text=\"b\"", describeNodeSelector(tag = null, text = "b"))
        assertEquals("tag=\"a\", text=\"b\"", describeNodeSelector(tag = "a", text = "b"))
    }

    private fun headlessAutomator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
}

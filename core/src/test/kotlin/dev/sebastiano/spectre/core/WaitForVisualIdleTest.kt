package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class WaitForVisualIdleTest {

    @Test
    fun `visual idle cancellation interrupts a cold capture wait`() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(100.milliseconds) {
                waitForVisualIdleInternal(
                    timeout = 5.seconds,
                    stableFrames = 1,
                    pollInterval = 1.milliseconds,
                    frameHash = { awaitCancellation() },
                )
            }
        }
    }

    @Test
    fun `cold frame capture receives the remaining wait budget before steady state cap`() =
        runTest {
            val clock = FakeClock()
            val budgets = mutableListOf<Long>()
            var captures = 0
            val hasher =
                BoundedFrameHasher(
                    steadyStateBudgetMs = 500,
                    sample = { budgetMs ->
                        budgets += budgetMs
                        val captureDurationMs = if (captures++ == 0) 3_600L else 100L
                        if (budgetMs < captureDurationMs) {
                            null
                        } else {
                            clock.advance(captureDurationMs.milliseconds)
                            7
                        }
                    },
                )

            waitForVisualIdleInternal(
                timeout = 5.seconds,
                stableFrames = 3,
                pollInterval = 100.milliseconds,
                frameHash = hasher::hash,
                clock = clock,
                sleep = clock::advance,
            )

            assertEquals(listOf(5_000L, 500L, 500L), budgets)
        }

    @Test
    fun `each visual-idle wait gets an independent cold capture budget`() = runTest {
        val firstBudgets = mutableListOf<Long>()
        val secondBudgets = mutableListOf<Long>()
        val firstWait =
            BoundedFrameHasher(
                steadyStateBudgetMs = 500,
                sample = { budgetMs ->
                    firstBudgets += budgetMs
                    1
                },
            )
        val secondWait =
            BoundedFrameHasher(
                steadyStateBudgetMs = 500,
                sample = { budgetMs ->
                    secondBudgets += budgetMs
                    1
                },
            )

        firstWait.hash(5_000)
        firstWait.hash(4_000)
        secondWait.hash(5_000)

        assertEquals(listOf(5_000L, 500L), firstBudgets)
        assertEquals(listOf(5_000L), secondBudgets)
    }

    @Test
    fun `waitForVisualIdle returns when stableFrames consecutive hashes match`() = runTest {
        val clock = FakeClock()
        val frames = ArrayDeque(listOf(1, 2, 3, 3, 3))
        val sampled = mutableListOf<Int>()

        waitForVisualIdleInternal(
            timeout = 1.seconds,
            stableFrames = 3,
            pollInterval = 16.milliseconds,
            frameHash = { _ ->
                val next = frames.removeFirst()
                sampled += next
                next
            },
            clock = clock,
            sleep = clock::advance,
        )

        assertEquals(listOf(1, 2, 3, 3, 3), sampled)
    }

    @Test
    fun `waitForVisualIdle resets when a new frame breaks the streak`() = runTest {
        val clock = FakeClock()
        val frames = ArrayDeque(listOf(1, 1, 2, 1, 1, 1))
        val sampled = mutableListOf<Int>()

        waitForVisualIdleInternal(
            timeout = 1.seconds,
            stableFrames = 3,
            pollInterval = 16.milliseconds,
            frameHash = { _ ->
                val next = frames.removeFirst()
                sampled += next
                next
            },
            clock = clock,
            sleep = clock::advance,
        )

        assertEquals(listOf(1, 1, 2, 1, 1, 1), sampled)
    }

    @Test
    fun `waitForVisualIdle throws when frames keep changing`() = runTest {
        val clock = FakeClock()
        var counter = 0

        val error =
            assertFailsWith<IdleTimeoutException> {
                waitForVisualIdleInternal(
                    timeout = 80.milliseconds,
                    stableFrames = 3,
                    pollInterval = 16.milliseconds,
                    frameHash = { _ -> counter++ },
                    clock = clock,
                    sleep = clock::advance,
                )
            }

        assertTrue(
            error.message?.contains("waitForVisualIdle") == true,
            "Should mention waitForVisualIdle: ${error.message}",
        )
        assertTrue(
            error.message?.contains("frames did not stabilise") == true,
            "Should attribute pure pixel churn to unstable frames: ${error.message}",
        )
        assertTrue(
            error.message?.contains("unsampleable") != true,
            "Must not claim unsampleable/budget failure when every sample returned a hash: ${error.message}",
        )
    }

    @Test
    fun `timeout names unsampleable samples when every frame hash is null`() = runTest {
        val clock = FakeClock()
        val error =
            assertFailsWith<IdleTimeoutException> {
                waitForVisualIdleInternal(
                    timeout = 50.milliseconds,
                    stableFrames = 3,
                    pollInterval = 10.milliseconds,
                    frameHash = { _ -> null },
                    clock = clock,
                    sleep = clock::advance,
                )
            }

        val message = error.message.orEmpty()
        assertTrue(
            message.contains("waitForVisualIdle timed out after 50ms"),
            "Should include wait name and timeout: $message",
        )
        assertTrue(
            Regex("""\d+/\d+ samples were unsampleable""").containsMatchIn(message),
            "Should report unsampleable sample counts: $message",
        )
        assertTrue(
            message.contains("capture budget") || message.contains("no Compose surface"),
            "Should hint at capture budget or missing surfaces: $message",
        )
    }

    @Test
    fun `timeout reports mixed unsampleable and unstable samples`() = runTest {
        val clock = FakeClock()
        var calls = 0
        val error =
            assertFailsWith<IdleTimeoutException> {
                waitForVisualIdleInternal(
                    timeout = 80.milliseconds,
                    stableFrames = 3,
                    pollInterval = 10.milliseconds,
                    frameHash = { _ ->
                        // Alternate null (budget/surface miss) with changing hashes so neither
                        // cause alone explains the timeout.
                        if (calls++ % 2 == 0) null else calls
                    },
                    clock = clock,
                    sleep = clock::advance,
                )
            }

        val message = error.message.orEmpty()
        assertTrue(
            Regex("""\d+/\d+ samples were unsampleable""").containsMatchIn(message),
            "Should report partial unsampleable counts: $message",
        )
        assertTrue(
            message.contains("frames did not stabilise"),
            "Should still mention unstable frames when some hashes were returned: $message",
        )
    }

    @Test
    fun `waitForVisualIdle throws if the streak only completes after the deadline`() = runTest {
        // 50ms timeout, 30ms pollInterval, stableFrames=2: streak completes at the second
        // identical sample (t=30) which is fine; but if the deadline already passed by the
        // time we accept the streak, we must throw rather than return success.
        val clock = FakeClock()
        // Using stableFrames=3 with timeout 50ms / pollInterval 30ms: third matching sample
        // lands at t=60, after the 50ms deadline.
        assertFailsWith<IdleTimeoutException> {
            waitForVisualIdleInternal(
                timeout = 50.milliseconds,
                stableFrames = 3,
                pollInterval = 30.milliseconds,
                frameHash = { _ -> 7 },
                clock = clock,
                sleep = clock::advance,
            )
        }
    }

    @Test
    fun `waitForVisualIdle passes the remaining timeout to the frame hash callback`() = runTest {
        val clock = FakeClock()
        val budgets = mutableListOf<Long>()

        assertFailsWith<IdleTimeoutException> {
            waitForVisualIdleInternal(
                timeout = 100.milliseconds,
                stableFrames = 5,
                pollInterval = 30.milliseconds,
                frameHash = { remainingMs ->
                    budgets += remainingMs
                    // Always changing → never streaks → loop runs until deadline.
                    budgets.size
                },
                clock = clock,
                sleep = clock::advance,
            )
        }

        assertTrue(budgets.first() == 100L, "First budget should equal full timeout: $budgets")
        assertTrue(
            budgets.zipWithNext().all { (prev, next) -> next <= prev },
            "Budget should monotonically decrease across polls: $budgets",
        )
    }

    @Test
    fun `visualIdleTimeoutDiagnostic only blames unstable frames when every sample hashed`() {
        assertEquals(
            "frames did not stabilise across 3 samples",
            visualIdleTimeoutDiagnostic(stableFrames = 3, sampleCount = 5, unsampleableCount = 0),
        )
    }

    @Test
    fun `visualIdleTimeoutDiagnostic prioritises fully unsampleable waits`() {
        val message =
            visualIdleTimeoutDiagnostic(stableFrames = 3, sampleCount = 4, unsampleableCount = 4)
        assertTrue(
            message.startsWith("4/4 samples were unsampleable"),
            "Should lead with unsampleable counts: $message",
        )
        assertTrue(
            message.contains("capture budget exceeded"),
            "Should name capture budget: $message",
        )
        assertTrue(
            !message.contains("frames did not stabilise"),
            "All-null waits should not claim pixel churn: $message",
        )
    }

    @Test
    fun `waitForVisualIdle requires positive stableFrames`() = runTest {
        val clock = FakeClock()
        assertFailsWith<IllegalArgumentException> {
            waitForVisualIdleInternal(
                timeout = 1.seconds,
                stableFrames = 0,
                pollInterval = 16.milliseconds,
                frameHash = { _ -> 0 },
                clock = clock,
                sleep = clock::advance,
            )
        }
    }

    @Test
    fun `waitForVisualIdle rejects EDT callers with a curated error`() {
        assumeLiveAwtAvailable()
        val automator = ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
        val errorRef = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        javax.swing.SwingUtilities.invokeAndWait {
            kotlinx.coroutines.runBlocking {
                errorRef.set(runCatching { automator.waitForVisualIdle() }.exceptionOrNull())
            }
        }
        val error = errorRef.get()
        assertTrue(error is IllegalStateException, "expected IllegalStateException, got $error")
        assertTrue(
            error.message?.contains(
                "waitForVisualIdle must not be called from the AWT event dispatch thread"
            ) == true,
            "expected curated EDT message, got: ${error.message}",
        )
    }
}

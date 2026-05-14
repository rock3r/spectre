package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class WaitForVisualIdleTest {

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

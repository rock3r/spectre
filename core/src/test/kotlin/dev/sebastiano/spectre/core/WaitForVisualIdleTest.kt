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
            frameHash = {
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
            frameHash = {
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
                    frameHash = { counter++ },
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
    fun `waitForVisualIdle requires positive stableFrames`() = runTest {
        val clock = FakeClock()
        assertFailsWith<IllegalArgumentException> {
            waitForVisualIdleInternal(
                timeout = 1.seconds,
                stableFrames = 0,
                pollInterval = 16.milliseconds,
                frameHash = { 0 },
                clock = clock,
                sleep = clock::advance,
            )
        }
    }
}

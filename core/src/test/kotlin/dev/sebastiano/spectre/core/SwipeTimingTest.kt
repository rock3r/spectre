package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class SwipeTimingTest {

    @Test
    fun `swipe pause uses gesture steps instead of point count`() {
        // 200ms / 12 steps = 16ms/step, with no Robot autoDelay overhead to subtract.
        val pauseMs = swipePauseMillis(200.milliseconds, steps = 12, autoDelayMs = 0)

        assertEquals(16, pauseMs)
    }

    @Test
    fun `swipe pause rejects non-positive steps`() {
        assertFailsWith<IllegalArgumentException> {
            swipePauseMillis(200.milliseconds, steps = 0, autoDelayMs = 0)
        }
    }
}

package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LiveAwtGeometryTest {
    @Test
    fun `matching final sample does not satisfy stability guard`() {
        val expected = Rectangle(40, 80, 300, 200)

        assertFailsWith<AssertionError> {
            assertStableAwtFrames(
                consecutiveMatches = 1,
                frames = arrayOf(expected to expected),
            )
        }
    }
}

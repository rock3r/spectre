package dev.sebastiano.spectre.recording

import kotlin.test.Test
import kotlin.test.assertFailsWith

class RecordingOptionsTest {

    @Test
    fun `non-positive frameRate is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecordingOptions(frameRate = 0) }
        assertFailsWith<IllegalArgumentException> { RecordingOptions(frameRate = -10) }
    }

    @Test
    fun `blank codec is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecordingOptions(codec = "") }
        assertFailsWith<IllegalArgumentException> { RecordingOptions(codec = "   ") }
    }

    @Test
    fun `negative screenIndex is rejected`() {
        assertFailsWith<IllegalArgumentException> { RecordingOptions(screenIndex = -1) }
    }
}

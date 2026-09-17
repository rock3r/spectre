@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

/**
 * Pins capture-failure taxonomy for [respondScreenshot]: native backends throw
 * [IllegalStateException] or [UnsupportedOperationException] (missing recording artifact, disabled
 * backend, non-Frame host). Both must map to `inputRejected` rather than an unclassified 500.
 */
class ScreenshotCaptureErrorMappingTest {

    @Test
    fun `unsupported capture backend maps to inputRejected`() {
        assertEquals(
            SpectreErrorCategory.InputRejected,
            mapScreenshotFailure(
                UnsupportedOperationException("Native window capture bridge is unavailable")
            ),
        )
    }

    @Test
    fun `illegal-state capture maps to inputRejected`() {
        assertEquals(
            SpectreErrorCategory.InputRejected,
            mapScreenshotFailure(IllegalStateException("capture failed")),
        )
    }

    @Test
    fun `cancellation is rethrown`() {
        assertFailsWith<CancellationException> {
            mapScreenshotFailure(CancellationException("cancelled"))
        }
    }
}

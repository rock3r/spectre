@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Window
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #611: on Windows and Linux X11 a title that another window shares no longer blocks native
 * capture. The recording bridge selects such a window by its owner process and frame instead.
 */
class DuplicateTitleNativeCaptureTest {

    @Test
    fun `duplicate window titles use the frame selector when the frames differ`() {
        // #611: Windows and Linux X11 helpers can select a window by owner pid and frame (#606),
        // so a title that another window shares no longer blocks native capture.
        assumeLiveAwtAvailable()
        val first =
            Frame("same title").apply {
                setBounds(10, 20, 100, 80)
                addNotify()
            }
        val second =
            Frame("same title").apply {
                setBounds(120, 30, 100, 80)
                addNotify()
            }
        val captured = mutableListOf<Window>()
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { window ->
                    captured += window
                    BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB)
                },
                nativeCaptureSelectsByFrame = { true },
            )
        try {
            backend.captureWindow(tracked(second))

            assertEquals(listOf<Window>(second), captured)
        } finally {
            first.dispose()
            second.dispose()
        }
    }

    @Test
    fun `duplicate window titles still fail when the frames match too`() {
        assumeLiveAwtAvailable()
        val first =
            Frame("same title").apply {
                setBounds(10, 20, 100, 80)
                addNotify()
            }
        val second =
            Frame("same title").apply {
                setBounds(10, 20, 100, 80)
                addNotify()
            }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { error("native capture must not guess between two windows") },
                nativeCaptureSelectsByFrame = { true },
            )
        try {
            val error =
                assertFailsWith<IllegalStateException> { backend.captureWindow(tracked(second)) }

            val message = error.message.orEmpty()
            assertTrue(message.contains("same title"), message)
            assertTrue(message.contains("frame"), message)
            assertTrue(message.contains(second.bounds.toString()), message)
        } finally {
            first.dispose()
            second.dispose()
        }
    }

    private fun tracked(window: Window): TrackedWindow =
        TrackedWindow("test", window, composePanel = null, isPopup = false)
}

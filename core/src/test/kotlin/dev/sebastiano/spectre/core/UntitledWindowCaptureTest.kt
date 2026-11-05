@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import javax.swing.JWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Window-scoped capture of hosts that have no title (#605): a Swing [JWindow] popup or a plain AWT
 * [Window]. The native helpers find these by their on-screen frame instead of by title, so core
 * must route them to the native backend rather than reject them.
 */
class UntitledWindowCaptureTest {

    @Test
    fun `tracked JWindow capture uses the native window backend`() {
        assumeLiveAwtAvailable()
        val owner = Frame("jwindow-owner")
        val popup =
            JWindow(owner).apply {
                setBounds(40, 50, 300, 200)
                addNotify()
            }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        var capturedWindow: Window? = null
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("should not fall back") },
                nativeCapture = { window ->
                    capturedWindow = window
                    expected
                },
                nativeCaptureBounds = { _, _, bounds, _ -> bounds },
            )
        try {
            val capture = backend.captureWindow(tracked(popup), Rectangle(popup.bounds))

            assertSame(expected, capture.image)
            assertSame(popup, capturedWindow)
            assertEquals(Rectangle(40, 50, 300, 200), capture.boundsOnScreen)
        } finally {
            popup.dispose()
            owner.dispose()
        }
    }

    @Test
    fun `plain AWT Window capture uses the native window backend`() {
        assumeLiveAwtAvailable()
        val window =
            Window(null as Frame?).apply {
                setBounds(40, 50, 300, 200)
                addNotify()
            }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("should not fall back") },
                nativeCapture = { expected },
                nativeCaptureBounds = { _, _, bounds, _ -> bounds },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(window)).image)
        } finally {
            window.dispose()
        }
    }

    private fun tracked(window: Window): TrackedWindow =
        TrackedWindow("test", window, composePanel = null, isPopup = false)
}

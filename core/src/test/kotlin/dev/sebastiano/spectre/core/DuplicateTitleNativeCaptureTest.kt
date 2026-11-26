@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
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
                isVisible = true
            }
        val second =
            Frame("same title").apply {
                setBounds(120, 30, 100, 80)
                isVisible = true
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
        val graphicsConfiguration =
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
        val requestedBounds = legalBounds(graphicsConfiguration)
        val first =
            Frame("same title", graphicsConfiguration).apply {
                isUndecorated = true
                bounds = requestedBounds
                isVisible = true
            }
        val second =
            Frame("same title", graphicsConfiguration).apply {
                isUndecorated = true
                bounds = requestedBounds
                isVisible = true
            }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { error("native capture must not guess between two windows") },
                nativeCaptureSelectsByFrame = { true },
            )
        try {
            // A real WM is allowed to place a newly mapped window somewhere other than its
            // pre-map requested position. Reapply the common frame after both maps, which is the
            // state this ambiguity test promises to exercise.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var consecutiveMatches = 0
            do {
                if (first.bounds != requestedBounds || second.bounds != requestedBounds) {
                    SwingUtilities.invokeAndWait {
                        first.bounds = requestedBounds
                        second.bounds = requestedBounds
                    }
                }
                Thread.sleep(25)
                if (first.bounds == requestedBounds && second.bounds == requestedBounds) {
                    consecutiveMatches++
                } else {
                    consecutiveMatches = 0
                }
            } while (consecutiveMatches < 10 && System.nanoTime() < deadline)
            assertEquals(requestedBounds, first.bounds, "first frame must match the selector")
            assertEquals(requestedBounds, second.bounds, "second frame must match the selector")
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

    private fun legalBounds(graphicsConfiguration: GraphicsConfiguration): Rectangle {
        val screen = graphicsConfiguration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
        val left = screen.x.toLong() + insets.left
        val top = screen.y.toLong() + insets.top
        val width = (screen.width.toLong() - insets.left - insets.right).coerceAtLeast(1)
        val height = (screen.height.toLong() - insets.top - insets.bottom).coerceAtLeast(1)
        val windowWidth = minOf(100L, width)
        val windowHeight = minOf(80L, height)
        return Rectangle(
            (left + (width - windowWidth) / 2).toInt(),
            (top + (height - windowHeight) / 2).toInt(),
            windowWidth.toInt(),
            windowHeight.toInt(),
        )
    }
}

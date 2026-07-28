@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Frame
import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Internal capture seam: native window pixels when available, Robot regions otherwise. */
internal interface ScreenCaptureBackend {
    fun captureRegion(region: Rectangle? = null): BufferedImage

    fun captureWindow(window: TrackedWindow): BufferedImage
}

internal class PlatformScreenCaptureBackend(
    private val regionCapture: (Rectangle?) -> BufferedImage,
    private val nativeCapture: (Frame) -> BufferedImage,
) : ScreenCaptureBackend {
    internal constructor(
        robotDriver: RobotDriver
    ) : this(robotDriver::screenshot, defaultNativeCapture())

    override fun captureRegion(region: Rectangle?): BufferedImage = regionCapture(region)

    override fun captureWindow(window: TrackedWindow): BufferedImage {
        val frame =
            window.window as? Frame ?: return captureRegion(window.composeSurfaceBoundsOnScreen)
        return try {
            nativeCapture(frame)
        } catch (e: IllegalStateException) {
            if (e.message?.contains(" is unavailable") == true) {
                captureRegion(window.composeSurfaceBoundsOnScreen)
            } else {
                throw e
            }
        }
    }

    private companion object {
        fun defaultNativeCapture(): (Frame) -> BufferedImage {
            val screenshotter = AutoScreenshotter()
            return { frame -> screenshotter.captureWindow(frame.asTitledWindow()) }
        }
    }
}

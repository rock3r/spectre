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
    private val nativeCaptureEnabled: () -> Boolean = { true },
) : ScreenCaptureBackend {
    internal constructor(
        robotDriver: RobotDriver
    ) : this(robotDriver::screenshot, defaultNativeCapture(), { robotDriver.allowsPlatformCapture })

    override fun captureRegion(region: Rectangle?): BufferedImage = regionCapture(region)

    override fun captureWindow(window: TrackedWindow): BufferedImage {
        val frame = window.window as? Frame ?: return captureRegion(window.window.bounds)
        if (!nativeCaptureEnabled() || hasAmbiguousNativeIdentity(frame)) {
            return captureRegion(window.window.bounds)
        }
        return try {
            nativeCapture(frame)
        } catch (e: IllegalStateException) {
            fallBackToRegionCapture(window, e)
        } catch (e: UnsupportedOperationException) {
            fallBackToRegionCapture(window, e)
        } catch (e: IllegalArgumentException) {
            fallBackToRegionCapture(window, e)
        }
    }

    private fun fallBackToRegionCapture(
        window: TrackedWindow,
        error: RuntimeException,
    ): BufferedImage {
        if (!shouldFallBackToRegionCapture(error)) throw error
        // Keep the fallback image in the same coordinate system as a native window image.
        // Compose content can be inset from a decorated Frame, so capturing only the surface
        // would make callers crop the title-bar offset a second time.
        return captureRegion(window.window.bounds)
    }

    private fun shouldFallBackToRegionCapture(error: RuntimeException): Boolean =
        error is UnsupportedOperationException ||
            (error is IllegalStateException &&
                (error.message?.contains(" is unavailable") == true ||
                    error.message?.let(::isMissingPlatformHelper) == true)) ||
            (error is IllegalArgumentException &&
                error.message?.contains("requires a non-blank window title") == true)

    private fun isMissingPlatformHelper(message: String): Boolean =
        message.contains("helper", ignoreCase = true) &&
            (message.contains("not found", ignoreCase = true) ||
                message.contains("not bundled", ignoreCase = true))

    private fun hasAmbiguousNativeIdentity(frame: Frame): Boolean {
        val title = frame.title
        return title.isNullOrBlank() ||
            Frame.getFrames().any { other ->
                other !== frame && other.isDisplayable && other.title == title
            }
    }

    private companion object {
        fun defaultNativeCapture(): (Frame) -> BufferedImage {
            val screenshotter = AutoScreenshotter()
            return { frame -> screenshotter.captureWindow(frame.asTitledWindow()) }
        }
    }
}

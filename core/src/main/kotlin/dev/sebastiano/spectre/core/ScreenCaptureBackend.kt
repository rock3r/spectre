@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException

/** Internal capture seam: native window pixels when available, Robot regions otherwise. */
internal interface ScreenCaptureBackend {
    fun captureRegion(region: Rectangle? = null): BufferedImage

    fun captureWindow(window: TrackedWindow): BufferedImage
}

internal class PlatformScreenCaptureBackend(
    private val regionCapture: (Rectangle?) -> BufferedImage,
    private val nativeCapture: (Frame) -> BufferedImage,
    private val nativeCaptureEnabled: () -> Boolean = { true },
    private val nativeCaptureDisambiguatesTitles: () -> Boolean = { false },
) : ScreenCaptureBackend {
    internal constructor(
        robotDriver: RobotDriver
    ) : this(
        robotDriver::screenshot,
        defaultNativeCapture(),
        { robotDriver.allowsPlatformCapture },
        ::defaultNativeCaptureDisambiguatesTitles,
    )

    override fun captureRegion(region: Rectangle?): BufferedImage = regionCapture(region)

    override fun captureWindow(window: TrackedWindow): BufferedImage {
        val frame = window.window as? Frame ?: return captureRegion(window.window.bounds)
        if (
            !nativeCaptureEnabled() ||
                (!nativeCaptureDisambiguatesTitles() && hasAmbiguousNativeIdentity(frame))
        ) {
            return captureRegion(window.window.bounds)
        }
        return try {
            normalizeNativeImage(nativeCapture(frame))
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

    private fun hasAmbiguousNativeIdentity(frame: Frame): Boolean {
        val title = frame.title
        if (title.isNullOrBlank()) return false
        return Frame.getFrames().any { other ->
            other !== frame && other.isDisplayable && other.title == title
        }
    }

    private companion object {
        fun defaultNativeCapture(): (Frame) -> BufferedImage {
            return nativeWindowCaptureFor(PlatformScreenCaptureBackend::class.java.classLoader)
                ?: {
                    throw UnsupportedOperationException(
                        "Native window capture bridge is unavailable"
                    )
                }
        }

        fun defaultNativeCaptureDisambiguatesTitles(): Boolean =
            System.getProperty("os.name").contains("mac", ignoreCase = true)
    }
}

/**
 * Loads the optional recording-owned native capture bridge without linking it into core.
 *
 * The injected core payload intentionally excludes recording and its transitive dependencies;
 * absence of the bridge must therefore remain a normal Robot-fallback condition.
 */
internal fun nativeWindowCaptureFor(classLoader: ClassLoader): ((Frame) -> BufferedImage)? {
    val bridge =
        try {
            Class.forName(NATIVE_WINDOW_CAPTURE_BRIDGE, false, classLoader)
        } catch (_: ClassNotFoundException) {
            return null
        }
    val capture =
        try {
            bridge.getMethod("captureWindow", Frame::class.java)
        } catch (_: NoSuchMethodException) {
            return null
        }
    return { frame ->
        try {
            capture.invoke(null, frame) as BufferedImage
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            if (cause is RuntimeException) throw cause
            if (cause is LinkageError) {
                throw UnsupportedOperationException(
                    "Native window capture bridge is unavailable",
                    cause,
                )
            }
            throw IllegalStateException("Native window capture bridge failed", cause)
        }
    }
}

private const val NATIVE_WINDOW_CAPTURE_BRIDGE: String =
    "dev.sebastiano.spectre.recording.NativeWindowCaptureBridge"

internal fun shouldFallBackToRegionCapture(error: RuntimeException): Boolean =
    error is UnsupportedOperationException ||
        (error is IllegalStateException &&
            (error.message?.contains(" is unavailable") == true ||
                error.message?.let(::isMissingPlatformHelper) == true ||
                error.message?.contains("Could not determine WM frame extents") == true)) ||
        (error is IllegalArgumentException &&
            error.message?.contains("requires a non-blank window title") == true)

private fun isMissingPlatformHelper(message: String): Boolean =
    message.contains("helper", ignoreCase = true) &&
        (message.contains("not found", ignoreCase = true) ||
            message.contains("not bundled", ignoreCase = true))

internal fun normalizeNativeImage(image: BufferedImage): BufferedImage {
    if (image.type == BufferedImage.TYPE_INT_ARGB && image.colorModel.colorSpace.isCS_sRGB)
        return image
    val normalized = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = normalized.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return normalized
}

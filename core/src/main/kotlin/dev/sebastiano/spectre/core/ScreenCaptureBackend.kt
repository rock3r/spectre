@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/** Internal capture seam: native window pixels when available, Robot regions otherwise. */
internal interface ScreenCaptureBackend {
    fun captureRegion(region: Rectangle? = null): BufferedImage

    fun captureWindow(
        window: TrackedWindow,
        windowBounds: Rectangle = Rectangle(window.window.bounds),
    ): WindowCapture
}

/** Pixels from a tracked window and the screen-space rectangle those pixels represent. */
internal data class WindowCapture(val image: BufferedImage, val boundsOnScreen: Rectangle)

internal class PlatformScreenCaptureBackend(
    private val regionCapture: (Rectangle?) -> BufferedImage,
    private val nativeCapture: (Frame) -> BufferedImage,
    private val nativeCaptureEnabled: () -> Boolean = { true },
    private val nativeCaptureDisambiguatesTitles: () -> Boolean = { false },
    private val nativeCaptureBounds: (Frame, BufferedImage, Rectangle) -> Rectangle =
        { frame, _, windowBounds ->
            nativeWindowCaptureBounds(
                osName = System.getProperty("os.name"),
                windowBounds = windowBounds,
                insets = frame.insets,
                isWayland = isWaylandSession(),
            )
        },
    private val visibleDesktopBounds: () -> Rectangle = ::virtualDesktopBounds,
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

    override fun captureWindow(window: TrackedWindow, windowBounds: Rectangle): WindowCapture {
        val frame =
            window.window as? Frame
                ?: return regionCapture(
                    visibleWindowCaptureBounds(windowBounds, visibleDesktopBounds())
                )
        if (
            !nativeCaptureEnabled() ||
                (!nativeCaptureDisambiguatesTitles() && hasAmbiguousNativeIdentity(frame))
        ) {
            return regionCapture(visibleWindowCaptureBounds(windowBounds, visibleDesktopBounds()))
        }
        return try {
            val image = normalizeNativeImage(nativeCapture(frame))
            WindowCapture(image, nativeCaptureBounds(frame, image, windowBounds))
        } catch (e: IllegalStateException) {
            fallBackToRegionCapture(windowBounds, e)
        } catch (e: UnsupportedOperationException) {
            fallBackToRegionCapture(windowBounds, e)
        } catch (e: IllegalArgumentException) {
            fallBackToRegionCapture(windowBounds, e)
        }
    }

    private fun fallBackToRegionCapture(
        windowBounds: Rectangle,
        error: RuntimeException,
    ): WindowCapture {
        if (!shouldFallBackToRegionCapture(error)) throw error
        // Keep the fallback image in the same coordinate system as a native window image.
        // Compose content can be inset from a decorated Frame, so capturing only the surface
        // would make callers crop the title-bar offset a second time.
        return regionCapture(visibleWindowCaptureBounds(windowBounds, visibleDesktopBounds()))
    }

    private fun regionCapture(bounds: Rectangle): WindowCapture =
        WindowCapture(captureRegion(bounds), Rectangle(bounds))

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
 * The Linux native helper captures a Frame's client area. Its pixels can be scaled to the display's
 * device resolution, but their screen coordinates remain in AWT logical units.
 */
internal fun nativeWindowCaptureBounds(
    osName: String,
    windowBounds: Rectangle,
    insets: java.awt.Insets,
    isWayland: Boolean,
): Rectangle {
    if (!osName.contains("linux", ignoreCase = true) || isWayland) return Rectangle(windowBounds)
    return Rectangle(
        windowBounds.x + insets.left,
        windowBounds.y + insets.top,
        windowBounds.width - insets.left - insets.right,
        windowBounds.height - insets.top - insets.bottom,
    )
}

internal fun isWaylandSession(
    getenv: (String) -> String? = System::getenv,
    runtimeDirHasWaylandSocket: (Path) -> Boolean = ::runtimeDirHasWaylandSocket,
): Boolean {
    if (getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)) return true
    if (!getenv("WAYLAND_DISPLAY").isNullOrBlank()) return true
    return getenv("XDG_RUNTIME_DIR")?.let(Path::of)?.let {
        runCatching { runtimeDirHasWaylandSocket(it) }.getOrDefault(false)
    } == true
}

internal fun visibleWindowCaptureBounds(
    windowBounds: Rectangle,
    desktopBounds: Rectangle,
): Rectangle = windowBounds.intersection(desktopBounds)

private fun runtimeDirHasWaylandSocket(runtimeDir: Path): Boolean =
    Files.isDirectory(runtimeDir) &&
        Files.list(runtimeDir).use { entries ->
            entries.anyMatch { it.fileName.toString().startsWith("wayland-") }
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
                error.message?.let(::isMissingLinuxScreenshotPipeline) == true ||
                error.message?.let(::isTimedOutNativeScreenshot) == true ||
                error.message?.let(::isUndiscoverableNativeWindow) == true ||
                error.message?.let(::isUnavailableLinuxNativeScreenshot) == true ||
                error.message?.let(::isUnavailableMacosNativeScreenshot) == true ||
                error.message?.let(::isUnavailableWindowsNativeScreenshot) == true ||
                error.message?.contains("Could not determine WM frame extents") == true ||
                (error.message == "Native window capture bridge failed" &&
                    error.cause is IOException))) ||
        (error is IllegalArgumentException &&
            error.message?.contains("requires a non-blank window title") == true)

private fun isMissingPlatformHelper(message: String): Boolean =
    message.contains("helper", ignoreCase = true) &&
        (message.contains("not found", ignoreCase = true) ||
            message.contains("not bundled", ignoreCase = true))

private fun isMissingLinuxScreenshotPipeline(message: String): Boolean =
    (message.contains("spawning gst-launch", ignoreCase = true) &&
        message.contains("No such file or directory", ignoreCase = true)) ||
        message.contains("gst-launch screenshot pipeline exited with status", ignoreCase = true)

private fun isTimedOutNativeScreenshot(message: String): Boolean =
    message.contains("Timed out waiting for spectre-window-capture to capture a window")

private fun isUndiscoverableNativeWindow(message: String): Boolean =
    message.contains("spectre-screencapture could not find", ignoreCase = true) ||
        message.contains("spectre-window-capture could not find", ignoreCase = true)

private fun isUnavailableLinuxNativeScreenshot(message: String): Boolean =
    message == "Linux screenshot helper failed" ||
        (message.startsWith("Timed out after") &&
            message.contains("waiting for Linux screenshot helper")) ||
        message.startsWith("spectre-wayland-helper did not exit within") ||
        message.startsWith("spectre-wayland-helper exited with non-zero status")

private fun isUnavailableMacosNativeScreenshot(message: String): Boolean =
    message == "spectre-screencapture screenshot failed" ||
        message.startsWith("spectre-screencapture's screenshot pipeline failed") ||
        message.startsWith("failed to start spectre-screencapture for TCC preflight:") ||
        message.startsWith("Screen Recording: DENIED")

private fun isUnavailableWindowsNativeScreenshot(message: String): Boolean =
    message.startsWith("spectre-window-capture failed to start.") ||
        message.contains("reported Windows Graphics Capture is unsupported") ||
        message.contains("Windows Graphics Capture pipeline failed")

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

@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/** Internal capture seam for explicit screen regions and identity-preserving native windows. */
internal interface ScreenCaptureBackend {
    fun captureRegion(region: Rectangle? = null): BufferedImage

    fun captureWindow(
        window: TrackedWindow,
        windowBounds: Rectangle = Rectangle(window.window.bounds),
        frameInsets: Insets = Insets(0, 0, 0, 0),
    ): WindowCapture
}

/** Pixels from a tracked window and the screen-space rectangle those pixels represent. */
internal data class WindowCapture(val image: BufferedImage, val boundsOnScreen: Rectangle)

internal class PlatformScreenCaptureBackend(
    private val regionCapture: (Rectangle?) -> BufferedImage,
    private val nativeCapture: (Frame) -> BufferedImage,
    private val nativeCaptureEnabled: () -> Boolean = { true },
    private val nativeCaptureDisambiguatesTitles: () -> Boolean = { false },
    private val nativeCaptureBounds: (Frame, BufferedImage, Rectangle, Insets) -> Rectangle =
        { _, _, windowBounds, frameInsets ->
            nativeWindowCaptureBounds(
                osName = System.getProperty("os.name"),
                windowBounds = windowBounds,
                insets = frameInsets,
                isWayland = isWaylandSession(),
            )
        },
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

    override fun captureWindow(
        window: TrackedWindow,
        windowBounds: Rectangle,
        frameInsets: Insets,
    ): WindowCapture {
        val frame =
            window.window as? Frame
                ?: throw UnsupportedOperationException(
                    "Window-scoped screenshots require a native backend for a Frame host. " +
                        "Use screenshot(region) only when a screen-region capture is explicitly intended."
                )
        if (!nativeCaptureEnabled()) {
            throw UnsupportedOperationException(
                "Native window capture is disabled for this RobotDriver. " +
                    "RobotDriver.headless() does not permit real screenshot capture."
            )
        }
        check(frame.isDisplayable) {
            "Native window capture target ${frame.title.quoteForMessage()} is no longer displayable. " +
                "Refresh the window list before requesting a window screenshot."
        }
        if (!nativeCaptureDisambiguatesTitles()) {
            ambiguousNativeIdentity(frame)?.let { candidates ->
                throw IllegalStateException(
                    "Native window capture cannot uniquely select title ${frame.title.quoteForMessage()}. " +
                        "Matching frames: ${candidates.joinToString()}. " +
                        "Provide criteria that identify one window, or request screenshot(region) explicitly."
                )
            }
        }
        val image = normalizeNativeImage(nativeCapture(frame))
        return WindowCapture(image, nativeCaptureBounds(frame, image, windowBounds, frameInsets))
    }

    private fun ambiguousNativeIdentity(frame: Frame): List<String>? {
        val title = frame.title
        if (title.isNullOrBlank()) return null
        val matches = Frame.getFrames().filter { it.isDisplayable && it.title == title }
        return matches
            .takeIf { it.size > 1 }
            ?.map { "title=${it.title.quoteForMessage()}, bounds=${it.bounds}" }
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

/**
 * Returns true when still/window capture should use the Linux Wayland/portal path.
 *
 * Mirrors [dev.sebastiano.spectre.recording.FfmpegBackend.detectWaylandSession] (recording cannot
 * depend on core and core cannot depend on recording — keep both in lockstep; #397).
 *
 * Order: explicit `SPECTRE_CAPTURE_BACKEND` → pure-X11 DISPLAY (Xvfb) wins over inherited Wayland
 * env → session type / WAYLAND_DISPLAY → residual wayland-* socket only when DISPLAY is unset.
 */
@Suppress("ReturnCount")
internal fun isWaylandSession(
    getenv: (String) -> String? = System::getenv,
    runtimeDirHasWaylandSocket: (Path) -> Boolean = ::runtimeDirHasWaylandSocket,
    displayIsPureX11: (String) -> Boolean = ::defaultDisplayIsPureX11,
): Boolean {
    when (getenv("SPECTRE_CAPTURE_BACKEND")?.trim()?.lowercase()) {
        "x11",
        "xorg",
        "xvfb" -> return false
        "wayland",
        "portal" -> return true
    }
    val display = getenv("DISPLAY")?.takeIf { it.isNotBlank() }
    if (display != null && displayIsPureX11(display)) return false
    if (getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)) return true
    if (!getenv("WAYLAND_DISPLAY").isNullOrBlank()) return true
    if (display != null) return false
    return getenv("XDG_RUNTIME_DIR")?.let(Path::of)?.let {
        runCatching { runtimeDirHasWaylandSocket(it) }.getOrDefault(false)
    } == true
}

private fun runtimeDirHasWaylandSocket(runtimeDir: Path): Boolean =
    Files.isDirectory(runtimeDir) &&
        Files.list(runtimeDir).use { entries ->
            entries.anyMatch { it.fileName.toString().startsWith("wayland-") }
        }

/** True when [display] is served by Xvfb or a non-XWayland X server (see recording twin). */
@Suppress("TooGenericExceptionCaught")
internal fun defaultDisplayIsPureX11(display: String): Boolean {
    if (linuxDisplayMatchesXvfbProcess(display)) return true
    return runCatching { xdpyinfoReportsPureX11(display) }.getOrDefault(false)
}

/** Scans Linux /proc pid cmdlines for an Xvfb process serving [display]. */
@Suppress("TooGenericExceptionCaught")
internal fun linuxDisplayMatchesXvfbProcess(display: String): Boolean {
    val displayToken = normalizeDisplayToken(display) ?: return false
    val proc = Path.of("/proc")
    if (!Files.isDirectory(proc)) return false
    return runCatching {
            Files.list(proc).use { stream ->
                stream.anyMatch { entry ->
                    val name = entry.fileName.toString()
                    if (name.toLongOrNull() == null) return@anyMatch false
                    val cmdline =
                        runCatching { Files.readAllBytes(entry.resolve("cmdline")) }.getOrNull()
                            ?: return@anyMatch false
                    val args =
                        cmdline.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
                    if (args.none { it == "Xvfb" || it.endsWith("/Xvfb") }) return@anyMatch false
                    args.any { arg ->
                        val token = normalizeDisplayToken(arg) ?: return@anyMatch false
                        token == displayToken
                    }
                }
            }
        }
        .getOrDefault(false)
}

internal fun normalizeDisplayToken(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val afterHost =
        when {
            trimmed.startsWith(":") -> trimmed
            trimmed.contains(':') -> trimmed.substringAfterLast(':').let { ":$it" }
            else -> return null
        }
    val num =
        afterHost.removePrefix(":").substringBefore('.').takeIf { it.isNotEmpty() } ?: return null
    if (num.toIntOrNull() == null) return null
    return ":$num"
}

@Suppress("TooGenericExceptionCaught")
private fun xdpyinfoReportsPureX11(display: String): Boolean {
    val process = ProcessBuilder("xdpyinfo", "-display", display).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val finished = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return false
    }
    if (process.exitValue() != 0) return false
    return !output.contains("XWAYLAND", ignoreCase = true)
}

/**
 * Loads the optional recording-owned native capture bridge without linking it into core.
 *
 * The injected core payload intentionally excludes recording and its transitive dependencies;
 * absence of the bridge makes implicit window capture fail loudly; callers may opt in to the
 * independent screen-region API when that is the capture they want.
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

private fun String?.quoteForMessage(): String = "\"${this.orEmpty()}\""

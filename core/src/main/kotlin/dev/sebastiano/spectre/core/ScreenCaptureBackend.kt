@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.core.capture.cropImageToScreenRegion
import java.awt.Dialog
import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/** Internal capture seam for explicit screen regions and identity-preserving native windows. */
internal interface ScreenCaptureBackend {
    /**
     * Logical-size region capture: one pixel per AWT screen unit regardless of display density.
     * This is the cheap frame source for visual-idle hashing and the backing call for the public
     * `screenshot(region)` API, whose 1:1 screen↔image mapping callers assert against.
     */
    fun captureRegion(region: Rectangle? = null): BufferedImage

    /**
     * Screen-pixel region capture for still **artifacts** (capture PNGs, CLI/MCP screenshots). A
     * 400x300dp region on a 2x display yields a 800x600 pixel PNG, matching what `Recorder` writes
     * for the same region. See [captureRegion] for the logical-size counterpart.
     */
    fun captureStillRegion(region: Rectangle? = null): BufferedImage

    fun captureWindow(
        window: TrackedWindow,
        windowBounds: Rectangle = Rectangle(window.window.bounds),
        frameInsets: Insets = Insets(0, 0, 0, 0),
    ): WindowCapture
}

/**
 * Pixels from a tracked window and the screen-space rectangle those pixels represent.
 *
 * The two are in **different units** whenever the display is scaled: native still helpers hand back
 * backing-store pixels (3200x2000 for a 1600x1000dp window at 2x) while [boundsOnScreen] stays in
 * AWT logical units. Consumers that need to map between them must go through
 * [dev.sebastiano.spectre.core.capture.screenRectToImageRect] rather than assuming 1:1.
 */
internal data class WindowCapture(val image: BufferedImage, val boundsOnScreen: Rectangle)

/**
 * Narrows a native window capture to [region] while preserving its device-pixel resolution.
 *
 * The crop runs in image space via [cropImageToScreenRegion], so a 2x still stays 2x; only the
 * reported [WindowCapture.boundsOnScreen] is expressed in logical units. Downsampling here instead
 * would make every still 1x while the recorder kept writing screen-pixel video for the same window.
 */
internal fun windowStillForRegion(capture: WindowCapture, region: Rectangle): WindowCapture {
    if (capture.boundsOnScreen == region) return capture
    val visibleRegion = region.intersection(capture.boundsOnScreen)
    return WindowCapture(
        image = cropImageToScreenRegion(capture.image, visibleRegion, capture.boundsOnScreen),
        boundsOnScreen = visibleRegion,
    )
}

@Suppress("LongParameterList") // Every collaborator is a seam the capture tests substitute.
internal class PlatformScreenCaptureBackend(
    private val regionCapture: (Rectangle?) -> BufferedImage,
    private val nativeCapture: (Window) -> BufferedImage,
    private val nativeCaptureEnabled: () -> Boolean = { true },
    private val nativeCaptureDisambiguatesTitles: () -> Boolean = { false },
    /**
     * True where the native helper can select a window by owner process and frame (#606): Windows
     * and Linux X11. Then a title that another window shares falls back to that selector (#611).
     */
    private val nativeCaptureSelectsByFrame: () -> Boolean = { false },
    private val nativeCaptureBounds: (Window, BufferedImage, Rectangle, Insets) -> Rectangle =
        { _, _, windowBounds, frameInsets ->
            nativeWindowCaptureBounds(
                osName = System.getProperty("os.name"),
                windowBounds = windowBounds,
                insets = frameInsets,
                isWayland = isWaylandSession(),
            )
        },
    private val deviceScaleRegionCapture: (Rectangle?) -> BufferedImage = regionCapture,
) : ScreenCaptureBackend {
    internal constructor(
        robotDriver: RobotDriver
    ) : this(
        regionCapture = robotDriver::screenshot,
        nativeCapture = defaultNativeCapture(),
        nativeCaptureEnabled = { robotDriver.allowsPlatformCapture },
        nativeCaptureDisambiguatesTitles = ::defaultNativeCaptureDisambiguatesTitles,
        nativeCaptureSelectsByFrame = { !isWaylandSession() },
        deviceScaleRegionCapture = robotDriver::screenshotAtDeviceScale,
    )

    override fun captureRegion(region: Rectangle?): BufferedImage = regionCapture(region)

    override fun captureStillRegion(region: Rectangle?): BufferedImage =
        deviceScaleRegionCapture(region)

    override fun captureWindow(
        window: TrackedWindow,
        windowBounds: Rectangle,
        frameInsets: Insets,
    ): WindowCapture {
        // A Frame or Dialog is found by its title. Any other host (a Swing JWindow popup, a plain
        // AWT Window) has no title, so the native helper finds it by its on-screen frame (#605).
        val nativeWindow = window.window
        if (!nativeCaptureEnabled()) {
            throw UnsupportedOperationException(
                "Native window capture is disabled for this RobotDriver. " +
                    "RobotDriver.headless() does not permit real screenshot capture."
            )
        }
        check(nativeWindow.isDisplayable) {
            "Native window capture target ${nativeWindow.title.quoteForMessage()} is no longer displayable. " +
                "Refresh the window list before requesting a window screenshot."
        }
        if (!nativeCaptureDisambiguatesTitles()) {
            val selectsByFrame = nativeCaptureSelectsByFrame()
            ambiguousNativeIdentity(nativeWindow, selectsByFrame)?.let { candidates ->
                val shared = if (selectsByFrame) "title and frame" else "title"
                error(
                    "Native window capture cannot uniquely select title ${nativeWindow.title.quoteForMessage()}: " +
                        "${candidates.size} windows share the same $shared. " +
                        "Matching windows: ${candidates.joinToString()}. " +
                        "Provide criteria that identify one window, or request screenshot(region) explicitly."
                )
            }
        }
        val image = normalizeNativeImage(nativeCapture(nativeWindow))
        return WindowCapture(
            image,
            nativeCaptureBounds(nativeWindow, image, windowBounds, frameInsets),
        )
    }

    /**
     * The windows that make [window]'s native selector ambiguous, or null when it is unique. With
     * [selectsByFrame], a shared title is fine as long as no other window with that title has the
     * same frame: the recording bridge then selects the window by its frame instead.
     */
    private fun ambiguousNativeIdentity(window: Window, selectsByFrame: Boolean): List<String>? {
        val title = window.title
        if (title.isNullOrBlank()) return null
        val sameTitle = Window.getWindows().filter { it.isDisplayable && it.title == title }
        val matches =
            if (selectsByFrame) sameTitle.filter { it.bounds == window.bounds } else sameTitle
        return matches
            .takeIf { it.size > 1 }
            ?.map { "title=${it.title.quoteForMessage()}, bounds=${it.bounds}" }
    }

    private companion object {
        fun defaultNativeCapture(): (Window) -> BufferedImage {
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
 * depend on core and core cannot depend on recording — keep both in lockstep). Screenshot gold OS
 * keys in `:testing` also call this function so committed golds follow the same session detection
 * as window capture.
 *
 * Order: explicit `SPECTRE_CAPTURE_BACKEND`. Without `DISPLAY`: session type / `WAYLAND_DISPLAY`,
 * then a residual `wayland-*` socket. With `DISPLAY`, Wayland env vars count only when backed by
 * evidence (#575): an Xwayland process serving `DISPLAY` means Wayland; a pure X11 server (Xvfb,
 * Xvnc, Xtigervnc, Xephyr, Xorg) means X11; otherwise the `WAYLAND_DISPLAY` socket must exist.
 */
@InternalSpectreApi
@Suppress("ReturnCount")
public fun isWaylandSession(
    getenv: (String) -> String? = System::getenv,
    runtimeDirHasWaylandSocket: (Path) -> Boolean = ::runtimeDirHasWaylandSocket,
    displayIsPureX11: (String) -> Boolean = ::defaultDisplayIsPureX11,
    displayIsXwayland: (String) -> Boolean = ::defaultDisplayIsXwayland,
    waylandSocketExists: (Path) -> Boolean = ::isWaylandSocket,
): Boolean {
    when (getenv("SPECTRE_CAPTURE_BACKEND")?.trim()?.lowercase()) {
        "x11",
        "xorg",
        "xvfb" -> return false
        "wayland",
        "portal" -> return true
    }
    val display = getenv("DISPLAY")?.takeIf { it.isNotBlank() }
    val sessionIsWayland = getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)
    val waylandDisplay = getenv("WAYLAND_DISPLAY")?.takeIf { it.isNotBlank() }
    if (display == null) {
        if (sessionIsWayland || waylandDisplay != null) return true
        return getenv("XDG_RUNTIME_DIR")?.let(Path::of)?.let {
            runCatching { runtimeDirHasWaylandSocket(it) }.getOrDefault(false)
        } == true
    }
    // DISPLAY is set. Residual sockets alone never override it (#397).
    if (!sessionIsWayland && waylandDisplay == null) return false
    if (displayIsXwayland(display)) return true
    if (displayIsPureX11(display)) return false
    val socket = resolveWaylandSocket(waylandDisplay, getenv("XDG_RUNTIME_DIR")) ?: return false
    return runCatching { waylandSocketExists(socket) }.getOrDefault(false)
}

private fun runtimeDirHasWaylandSocket(runtimeDir: Path): Boolean =
    Files.isDirectory(runtimeDir) &&
        Files.list(runtimeDir).use { entries ->
            entries.anyMatch { it.fileName.toString().startsWith("wayland-") }
        }

internal fun nativeWindowCaptureFor(classLoader: ClassLoader): ((Window) -> BufferedImage)? {
    val bridge =
        try {
            Class.forName(NATIVE_WINDOW_CAPTURE_BRIDGE, false, classLoader)
        } catch (_: ClassNotFoundException) {
            return null
        }
    val capture =
        try {
            bridge.getMethod("captureWindow", Window::class.java)
        } catch (_: NoSuchMethodException) {
            return null
        }
    return { window ->
        try {
            capture.invoke(null, window) as BufferedImage
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

private val Window.title: String?
    get() =
        when (this) {
            is Frame -> title
            is Dialog -> title
            else -> null
        }

internal const val NATIVE_WINDOW_CAPTURE_BRIDGE: String =
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

package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.FramedWindow
import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.TitleDiscriminationAware
import dev.sebastiano.spectre.recording.screencapturekit.TitleDiscriminator
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.windows.WindowsWindowScreenshotter
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.lang.ProcessHandle

/**
 * Captures still screenshots from desktop surfaces.
 *
 * [captureWindow] uses true window-targeted capture where Spectre has one available: macOS
 * ScreenCaptureKit and the native Windows helper on Windows. On Linux, Spectre uses the bundled
 * helper: GStreamer `ximagesrc` on Xorg/Xvfb and portal/PipeWire one-frame capture on Wayland.
 * Linux Xorg/Xvfb callers must keep the target visible/frontmost; Wayland callers must accept the
 * compositor portal dialog.
 */
public class AutoScreenshotter
internal constructor(
    private val sckScreenshotter: WindowScreenshotter,
    private val windowsWindowScreenshotter: WindowScreenshotter?,
    private val linuxWindowScreenshotter: WindowScreenshotter?,
    private val isMacOs: () -> Boolean,
    private val isWindows: () -> Boolean,
    private val isLinux: () -> Boolean,
    private val isWayland: () -> Boolean,
) {

    public constructor(
        sckScreenshotter: WindowScreenshotter = ScreenCaptureKitScreenshotter(),
        windowsWindowScreenshotter: WindowScreenshotter? = defaultWindowsWindowScreenshotter(),
    ) : this(
        sckScreenshotter = sckScreenshotter,
        windowsWindowScreenshotter = windowsWindowScreenshotter,
        linuxWindowScreenshotter = defaultLinuxWindowScreenshotter,
        isMacOs = ::defaultIsMacOs,
        isWindows = ::defaultIsWindows,
        isLinux = ::defaultIsLinux,
        isWayland = ::defaultIsWayland,
    )

    public fun captureWindow(
        window: TitledWindow,
        windowOwnerPid: Long = ProcessHandle.current().pid(),
    ): BufferedImage {
        if (isMacOs()) {
            return try {
                sckScreenshotter.captureWindow(window, windowOwnerPid)
            } catch (e: HelperNotBundledException) {
                throw unavailable("macOS window screenshot", e)
            }
        }
        if (isWindows()) {
            return withTitleForCapture(window) {
                windowsWindowScreenshotter?.captureWindow(window, windowOwnerPid)
                    ?: throw unavailable("Windows window screenshot", null)
            }
        }
        if (isLinux()) {
            return withTitleForCapture(window) {
                linuxWindowScreenshotter?.captureWindow(window, windowOwnerPid)
                    ?: throw unavailable(
                        if (isWayland()) {
                            "Linux Wayland window screenshot"
                        } else {
                            "Linux X11 window screenshot"
                        },
                        null,
                    )
            }
        }
        throw UnsupportedOperationException(
            "AutoScreenshotter.captureWindow is unsupported on this platform because no native " +
                "window screenshot backend is available."
        )
    }

    /**
     * Still screenshot of a window that belongs to another process, found by [title] among the
     * windows of [windowOwnerPid].
     *
     * The Spectre daemon uses this on the attach path (#559), so the capture runs through the
     * daemon's own signed helper instead of inside the target JVM. Spectre cannot rename a window
     * in another process, so [title] must already identify one window of [windowOwnerPid]. The
     * image shows the whole native window, the same as [captureWindow] produces.
     *
     * Supported on macOS (ScreenCaptureKit) and Windows (Windows Graphics Capture). On Linux this
     * throws [UnsupportedOperationException]: the Linux helpers need the window in this process.
     */
    public fun captureWindowByTitle(title: String, windowOwnerPid: Long): BufferedImage {
        require(title.isNotBlank()) { "captureWindowByTitle requires a non-blank title" }
        val window = RemoteTitledWindow(title)
        if (isMacOs()) {
            return try {
                sckScreenshotter.captureWindow(window, windowOwnerPid)
            } catch (e: HelperNotBundledException) {
                throw unavailable("macOS window screenshot", e)
            }
        }
        if (isWindows()) {
            return windowsWindowScreenshotter?.captureWindow(window, windowOwnerPid)
                ?: throw unavailable("Windows window screenshot", null)
        }
        throw UnsupportedOperationException(
            "AutoScreenshotter.captureWindowByTitle is unsupported on this platform: the native " +
                "window screenshot backend can only capture windows of this process."
        )
    }

    private fun unavailable(mode: String, cause: Throwable?): IllegalStateException {
        val detail = cause?.message?.takeIf { it.isNotBlank() } ?: cause?.javaClass?.simpleName
        val message =
            if (detail == null) "$mode is unavailable." else "$mode is unavailable: $detail"
        return IllegalStateException(message, cause)
    }

    private fun <T> withTitleForCapture(window: TitledWindow, capture: () -> T): T {
        // An untitled host is selected by its frame; it has no title to rewrite.
        if (window is FramedWindow) return capture()
        if (!window.title.isNullOrBlank()) return capture()
        val discriminator = TitleDiscriminator(window)
        discriminator.apply()
        return try {
            capture()
        } finally {
            val wasInterrupted = Thread.interrupted()
            try {
                discriminator.restore()
            } finally {
                if (wasInterrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private companion object {
        fun defaultIsMacOs(): Boolean = HostPlatform.isMacOs()

        fun defaultIsWindows(): Boolean = HostPlatform.isWindows()

        fun defaultIsLinux(): Boolean = HostPlatform.isLinux()

        fun defaultIsWayland(): Boolean = HostPlatform.isWayland()

        @Suppress("TooGenericExceptionCaught")
        fun defaultWindowsWindowScreenshotter(): WindowScreenshotter? {
            if (!defaultIsWindows()) return null
            return try {
                WindowsWindowScreenshotter()
            } catch (_: Throwable) {
                null
            }
        }

        val defaultLinuxWindowScreenshotter: LinuxNativeScreenshotter? by lazy {
            if (defaultIsLinux()) LinuxNativeScreenshotter() else null
        }
    }
}

public interface WindowScreenshotter {
    public fun captureWindow(
        window: TitledWindow,
        windowOwnerPid: Long = ProcessHandle.current().pid(),
    ): BufferedImage
}

public interface RegionScreenshotter {
    public fun captureRegion(region: Rectangle): BufferedImage
}

/**
 * A window of another process, known only by its title. Its title is never rewritten: a title
 * discriminator would only rename this local object, and the helper would then search the target
 * process for a title that does not exist.
 */
private class RemoteTitledWindow(title: String) : TitledWindow, TitleDiscriminationAware {
    override var title: String? = title

    override val bounds: Rectangle = Rectangle()

    override val requiresTitleDiscriminator: Boolean = false
}

package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.awt.image.BufferedImage
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Optional runtime bridge for core's window-scoped still capture route.
 *
 * This type intentionally lives in `:recording`: injected core loads it only when recording is
 * already available to the host, preserving the inject payload's dependency boundary.
 */
internal object NativeWindowCaptureBridge {
    private val screenshotter: AutoScreenshotter by lazy(::AutoScreenshotter)
    private val captureLocks = WeakHashMap<Window, ReentrantLock>()
    @Volatile private var cachedPlatformCaptureUsable: Boolean? = null
    private val platformCaptureUsableLock = Any()

    /**
     * True when the host can actually run a native still (not merely load this class).
     *
     * Linux stills spawn `gst-launch-1.0` via the bundled helper. If that binary is missing,
     * visual- idle treats native capture as unavailable and falls back to Robot region sampling
     * (#503). An interrupted probe is not cached: a short first `waitForVisualIdle` budget must not
     * pin Robot for the rest of the JVM.
     */
    @JvmStatic
    @JvmName("isPlatformCaptureUsable")
    internal fun isPlatformCaptureUsable(): Boolean {
        cachedPlatformCaptureUsable?.let {
            return it
        }
        return synchronized(platformCaptureUsableLock) {
            cachedPlatformCaptureUsable
                ?: computePlatformCaptureUsable().also { cachedPlatformCaptureUsable = it }
        }
    }

    internal fun computePlatformCaptureUsable(
        isLinux: () -> Boolean = HostPlatform::isLinux,
        gstLaunchAvailable: () -> Boolean = { LinuxCaptureDependencies.isGstLaunchAvailable() },
    ): Boolean = if (isLinux()) gstLaunchAvailable() else true

    @JvmStatic
    @JvmName("captureWindow")
    internal fun captureWindow(window: Window): BufferedImage =
        withCaptureLock(window) {
            val titledWindow =
                when (window) {
                    is Frame -> window.asTitledWindow()
                    is Dialog -> window.asTitledWindow()
                    else ->
                        throw UnsupportedOperationException(
                            "Native window capture requires a Frame or Dialog host"
                        )
                }
            screenshotter.captureWindow(titledWindow)
        }

    internal fun <T> withCaptureLock(window: Window, action: () -> T): T {
        val lock = captureLockFor(window)
        if (EventQueue.isDispatchThread()) {
            check(lock.tryLock()) {
                "Native window capture is unavailable while another capture for this window is in progress"
            }
        } else {
            lock.lock()
        }
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    internal fun captureLockFor(window: Window): ReentrantLock =
        synchronized(captureLocks) { captureLocks.getOrPut(window, ::ReentrantLock) }
}

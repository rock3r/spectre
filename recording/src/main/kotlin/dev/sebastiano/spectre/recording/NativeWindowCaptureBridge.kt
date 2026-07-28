package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.EventQueue
import java.awt.Frame
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
    private val captureLocks = WeakHashMap<Frame, ReentrantLock>()

    @JvmStatic
    @JvmName("captureWindow")
    internal fun captureWindow(frame: Frame): BufferedImage =
        withCaptureLock(frame) { screenshotter.captureWindow(frame.asTitledWindow()) }

    internal fun <T> withCaptureLock(frame: Frame, action: () -> T): T {
        val lock = captureLockFor(frame)
        if (EventQueue.isDispatchThread()) {
            check(lock.tryLock()) {
                "Native window capture is unavailable while another capture for this frame is in progress"
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

    internal fun captureLockFor(frame: Frame): ReentrantLock =
        synchronized(captureLocks) { captureLocks.getOrPut(frame, ::ReentrantLock) }
}

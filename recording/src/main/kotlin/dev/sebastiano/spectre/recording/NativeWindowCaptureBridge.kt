package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Frame
import java.awt.image.BufferedImage
import java.util.WeakHashMap

/**
 * Optional runtime bridge for core's window-scoped still capture route.
 *
 * This type intentionally lives in `:recording`: injected core loads it only when recording is
 * already available to the host, preserving the inject payload's dependency boundary.
 */
internal object NativeWindowCaptureBridge {
    private val screenshotter: AutoScreenshotter by lazy(::AutoScreenshotter)
    private val captureLocks = WeakHashMap<Frame, Any>()

    @JvmStatic
    @JvmName("captureWindow")
    internal fun captureWindow(frame: Frame): BufferedImage =
        synchronized(captureLockFor(frame)) { screenshotter.captureWindow(frame.asTitledWindow()) }

    internal fun captureLockFor(frame: Frame): Any =
        synchronized(captureLocks) { captureLocks.getOrPut(frame, ::Any) }
}

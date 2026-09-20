package dev.sebastiano.spectre.recording

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Host tools required by Linux native stills / recording.
 *
 * Core asks [NativeWindowCaptureBridge.isPlatformCaptureUsable] rather than linking this type, so
 * inject payloads without recording stay free of GStreamer.
 */
internal object LinuxCaptureDependencies {
    /**
     * `true` when `gst-launch-1.0 --version` exits 0, `false` when the binary is missing or exits
     * non-zero, and `null` when the probe times out (cold registry scan). A timeout is inconclusive
     * and must not be negative-cached.
     */
    fun isGstLaunchAvailable(launch: () -> Process = ::startGstLaunchVersion): Boolean? {
        val process =
            try {
                launch()
            } catch (_: IOException) {
                return false
            }
        return try {
            val finished = process.waitFor(GST_LAUNCH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                null
            } else {
                process.exitValue() == 0
            }
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            // Do not return false: NativeWindowCaptureBridge caches the first completed
            // probe for the JVM lifetime. A cancelled waitForVisualIdle budget must not
            // permanently mark GStreamer missing (#503 / #355).
            throw interrupted
        }
    }

    private fun startGstLaunchVersion(): Process =
        ProcessBuilder(GST_LAUNCH, "--version").redirectErrorStream(true).start()
}

private const val GST_LAUNCH: String = "gst-launch-1.0"
private const val GST_LAUNCH_PROBE_TIMEOUT_MS: Long = 3_000

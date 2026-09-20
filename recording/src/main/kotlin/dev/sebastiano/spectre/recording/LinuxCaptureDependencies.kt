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
     * `true` when `gst-launch-1.0 --version` exits 0, `false` when the binary is confirmed missing
     * or exits non-zero, and `null` when the probe is inconclusive (timeout or a transient
     * `ProcessBuilder.start()` failure). Inconclusive results must not be negative-cached.
     */
    fun isGstLaunchAvailable(launch: () -> Process = ::startGstLaunchVersion): Boolean? {
        val process =
            try {
                launch()
            } catch (error: IOException) {
                return if (isConfirmedMissingExecutable(error)) false else null
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

    private fun isConfirmedMissingExecutable(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("Cannot run program", ignoreCase = true) ||
            message.contains("No such file or directory", ignoreCase = true) ||
            message.contains("error=2,", ignoreCase = true) ||
            message.contains("error=2)", ignoreCase = true)
    }
}

private const val GST_LAUNCH: String = "gst-launch-1.0"
private const val GST_LAUNCH_PROBE_TIMEOUT_MS: Long = 3_000

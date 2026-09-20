package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.DefaultWaylandHelperBinaryExtractor
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
     * `true` when `gst-launch-1.0 --version` exits 0 and, when `gst-inspect-1.0` is present, every
     * required still-capture element is present. `gst-inspect-1.0` is optional: the screenshot
     * pipeline only spawns `gst-launch-1.0`. A missing inspector must not be cached as native
     * unavailability. `false` when launch or a required element is confirmed missing. `null` when
     * the probe is inconclusive (timeout or a transient `ProcessBuilder.start()` failure).
     */
    fun isGstLaunchAvailable(
        inspectElement: (String) -> Boolean? = ::inspectGstElement,
        requiredElements: List<String> = requiredStillCaptureElements(),
        inspectAvailable: () -> Boolean? = ::probeGstInspectVersion,
        launch: () -> Process = ::startGstLaunchVersion,
    ): Boolean? {
        val version = awaitGstProcess(launch) ?: return null
        if (!version) return false
        when (inspectAvailable()) {
            false -> return true
            null -> return null
            true -> Unit
        }
        for (element in requiredElements) {
            when (inspectElement(element)) {
                true -> Unit
                false -> return false
                null -> return null
            }
        }
        return true
    }

    private fun startGstLaunchVersion(): Process = gstProbeBuilder(GST_LAUNCH, "--version").start()

    private fun inspectGstElement(element: String): Boolean? = awaitGstProcess {
        gstProbeBuilder(GST_INSPECT, element).start()
    }
}

/**
 * Discard probe stdout/stderr. `gst-inspect-1.0` element reports can exceed the OS pipe buffer; if
 * the child blocks on a full unread pipe, [awaitGstProcess] times out and visual-idle falls back to
 * Robot even when GStreamer is healthy.
 */
internal fun gstProbeBuilder(vararg command: String): ProcessBuilder =
    ProcessBuilder(*command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)

internal fun probeGstInspectVersion(): Boolean? = awaitGstProcess {
    gstProbeBuilder(GST_INSPECT, "--version").start()
}

internal fun requiredStillCaptureElements(
    isWayland: () -> Boolean = HostPlatform::isWayland
): List<String> =
    if (isWayland()) {
        WAYLAND_STILL_CAPTURE_ELEMENTS
    } else {
        X11_STILL_CAPTURE_ELEMENTS
    }

internal fun awaitGstProcess(launch: () -> Process): Boolean? {
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

internal fun isConfirmedMissingExecutable(error: IOException): Boolean {
    val message = error.message.orEmpty()
    // ProcessBuilder.start() prefixes every launch failure with "Cannot run program"
    // regardless of errno. Only ENOENT is a confirmed missing binary; error=13 /
    // error=24 and similar stay inconclusive so they are not negative-cached.
    return message.contains("No such file or directory", ignoreCase = true) ||
        message.contains("error=2,", ignoreCase = true) ||
        message.contains("error=2)", ignoreCase = true)
}

internal fun isLinuxNativeHelperBundled(): Boolean =
    DefaultWaylandHelperBinaryExtractor.instance.isBundled()

private const val GST_LAUNCH: String = "gst-launch-1.0"
private const val GST_INSPECT: String = "gst-inspect-1.0"
private const val GST_LAUNCH_PROBE_TIMEOUT_MS: Long = 3_000

internal val X11_STILL_CAPTURE_ELEMENTS: List<String> =
    listOf("ximagesrc", "videoconvert", "pngenc")
internal val WAYLAND_STILL_CAPTURE_ELEMENTS: List<String> =
    listOf("pipewiresrc", "videocrop", "videoconvert", "pngenc")

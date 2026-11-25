@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.core.capture.cropImageToScreenRegion
import dev.sebastiano.spectre.core.capture.normalizeImageToScreenBounds
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.IOException

/**
 * Captures one tracked Compose surface for visual-idle frame hashing.
 *
 * When [nativeWindowCaptureAvailable] is true, uses window-scoped pixels via
 * [ScreenCaptureBackend.captureWindow] and crops to [surfaceRegion]. Failures do **not** fall back
 * to screen-region capture (that would reintroduce occlusion sensitivity #355 exists to remove), A
 * caller can explicitly opt into [ScreenCaptureBackend.captureRegion] when native capture is
 * unavailable; the default never substitutes a desktop crop for a window capture.
 *
 * Returning `null` for other native failures makes the visual-idle streak reset so the wait times
 * out rather than reporting fake stability.
 *
 * When the native bridge is absent, disabled, or not usable, fails unless the caller opted in.
 */
internal fun captureSurfaceForVisualIdle(
    backend: ScreenCaptureBackend,
    window: TrackedWindow,
    surfaceRegion: Rectangle,
    windowBounds: Rectangle,
    frameInsets: Insets,
    nativeWindowCaptureAvailable: Boolean,
    allowScreenRegionFallback: Boolean = false,
): BufferedImage? {
    if (!nativeWindowCaptureAvailable) {
        return if (allowScreenRegionFallback) backend.captureRegion(surfaceRegion)
        else throw visualIdleNativeCaptureUnavailable()
    }
    return try {
        val capture = backend.captureWindow(window, windowBounds, frameInsets)
        val normalized = normalizeImageToScreenBounds(capture.image, capture.boundsOnScreen)
        if (capture.boundsOnScreen == surfaceRegion) {
            normalized
        } else {
            val visibleRegion = surfaceRegion.intersection(capture.boundsOnScreen)
            if (visibleRegion.isEmpty) {
                null
            } else {
                cropImageToScreenRegion(normalized, visibleRegion, capture.boundsOnScreen)
            }
        }
    } catch (error: UnsupportedOperationException) {
        // A window Wayland cannot capture yet stays unsupported for the whole wait: say so now.
        if (isWaylandCaptureFailFast(error)) throw error
        null
    } catch (error: IllegalStateException) {
        // A missing Wayland grant or an unusable Wayland window capture does not get better by
        // waiting: surface it now instead of timing out on "unsampleable" frames.
        if (isWaylandCaptureFailFast(error)) throw error
        if (isNativeCaptureHelperUnusable(error)) {
            if (allowScreenRegionFallback) backend.captureRegion(surfaceRegion)
            else throw visualIdleNativeCaptureUnavailable(error)
        } else {
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun visualIdleNativeCaptureUnavailable(cause: Throwable? = null): IllegalStateException =
    IllegalStateException(
        "waitForVisualIdle requires native window capture, but it is unavailable. " +
            "Install GStreamer on Linux, add the matching spectre-recording-<os> runtime " +
            "artifact, and on Windows use an interactive desktop (not a GitHub-hosted runner). " +
            "If occlusion-sensitive screen crops are acceptable, explicitly call " +
            "waitForVisualIdle(allowScreenRegionFallback = true).",
        cause,
    )

/**
 * Hashes every tracked surface in order. Returns `null` if any surface cannot be sampled (empty
 * surface list is the caller's concern — they should also return null).
 */
internal fun hashTrackedSurfacesForVisualIdle(
    surfaces: List<Pair<TrackedWindow, Rectangle>>,
    windowBoundsFor: (TrackedWindow) -> Rectangle,
    frameInsetsFor: (TrackedWindow) -> Insets,
    backend: ScreenCaptureBackend,
    nativeWindowCaptureAvailable: Boolean,
    allowScreenRegionFallback: Boolean = false,
): Int? {
    if (surfaces.isEmpty()) return null
    val hashes = IntArray(surfaces.size)
    for (i in surfaces.indices) {
        val (window, region) = surfaces[i]
        val image =
            captureSurfaceForVisualIdle(
                backend = backend,
                window = window,
                surfaceRegion = region,
                windowBounds = windowBoundsFor(window),
                frameInsets = frameInsetsFor(window),
                nativeWindowCaptureAvailable = nativeWindowCaptureAvailable,
                allowScreenRegionFallback = allowScreenRegionFallback,
            ) ?: return null
        hashes[i] = imageHash(image)
    }
    return hashes.contentHashCode()
}

/**
 * True when the recording-owned native still bridge is on the classpath and this host is allowed to
 * use it.
 *
 * Atomic [ComposeAutomator.capture] applies the same checks through
 * [atomicCaptureNativeStillUnavailableReason], and fails when they do not pass instead of taking a
 * Robot crop (#355, #559). GitHub Actions hosted Windows is still treated as non-interactive (WGC
 * needs a console).
 */
internal fun isNativeWindowCaptureBridgePresent(
    classLoader: ClassLoader = VisualIdleSurfaceCapture::class.java.classLoader,
    allowsPlatformCapture: Boolean = true,
    osName: String = System.getProperty("os.name").orEmpty(),
    getenv: (String) -> String? = System::getenv,
): Boolean {
    if (!allowsPlatformCapture) return false
    if (isNonInteractiveHostedWindows(osName, getenv)) return false
    return nativeWindowCaptureFor(classLoader) != null
}

/**
 * True when window-scoped native stills can be used for visual-idle sampling.
 *
 * Requires the recording-owned bridge **and** a host where the platform helper can actually run.
 * Class presence alone is not enough: Linux stills need `gst-launch-1.0` (#503). When the helper
 * cannot run, [waitForVisualIdle] fails unless its explicit screen-region fallback is enabled.
 */
internal fun isNativeWindowCaptureAvailable(
    classLoader: ClassLoader = VisualIdleSurfaceCapture::class.java.classLoader,
    allowsPlatformCapture: Boolean = true,
    osName: String = System.getProperty("os.name").orEmpty(),
    getenv: (String) -> String? = System::getenv,
    platformCaptureUsable: (ClassLoader) -> Boolean = ::isNativePlatformCaptureUsable,
): Boolean {
    if (!isNativeWindowCaptureBridgePresent(classLoader, allowsPlatformCapture, osName, getenv)) {
        return false
    }
    return platformCaptureUsable(classLoader)
}

/**
 * True when a native-window capture failure means the helper cannot run, not that this window was
 * ambiguous or occluded.
 *
 * [captureSurfaceForVisualIdle] reports this case immediately unless region fallback was requested.
 */
internal fun isNativeCaptureHelperUnusable(error: Throwable): Boolean {
    val chain = generateSequence(error) { it.cause }.toList()
    val messages = chain.mapNotNull { it.message }
    if (
        messages.any { message ->
            message.contains("Linux", ignoreCase = true) &&
                message.contains("screenshot is unavailable", ignoreCase = true)
        }
    ) {
        return true
    }
    if (messages.any { it.contains("Bundled helper binary not found", ignoreCase = true) }) {
        return true
    }
    // Helper Event.Error for a missing gst-launch process (screenshot.rs spawn context).
    // Later pipeline strings also mention gst-launch ("did not exit within", "pipeline
    // exited with status") and must stay unsampleable so #355 does not region-substitute.
    if (messages.any { it.contains("spawning gst-launch", ignoreCase = true) }) {
        return true
    }
    // LinuxNativeScreenshotter wraps every IOException as "Linux screenshot helper failed".
    // Only a confirmed missing binary (ENOENT) means the helper cannot run (#503). Permission,
    // FD exhaustion, pipe, and decode failures stay unsampleable so #355 does not
    // region-substitute. ProcessBuilder.start() prefixes every launch failure with
    // "Cannot run program", so that prefix alone is not ENOENT.
    return chain.filterIsInstance<IOException>().any { ioe ->
        val message = ioe.message.orEmpty()
        message.contains("No such file or directory", ignoreCase = true) ||
            message.contains("error=2,", ignoreCase = true) ||
            message.contains("error=2)", ignoreCase = true)
    }
}

/**
 * `:recording`'s Linux Wayland exceptions that mean "fail now, with this message": a missing portal
 * grant (`spectre permissions request`), or a window that cannot get a Mutter window stream.
 * Matched by class name because core cannot link `:recording`; keep in sync with
 * `WaylandCaptureUnavailableException` in `WaylandHelperProtocol.kt`.
 */
internal val WAYLAND_CAPTURE_FAIL_FAST_EXCEPTIONS: Set<String> =
    setOf(
        "dev.sebastiano.spectre.recording.portal.WaylandCaptureUnavailableException",
        "dev.sebastiano.spectre.recording.portal.WaylandConsentNotGrantedException",
        "dev.sebastiano.spectre.recording.portal.WaylandWindowCaptureUnsupportedException",
    )

/** True when [error], or a cause, is (a subclass of) one of [classNames]. */
internal fun isWaylandCaptureFailFast(
    error: Throwable,
    classNames: Set<String> = WAYLAND_CAPTURE_FAIL_FAST_EXCEPTIONS,
): Boolean =
    generateSequence(error) { it.cause }
        .any { cause ->
            generateSequence<Class<*>>(cause.javaClass) { it.superclass }
                .any { it.name in classNames }
        }

/**
 * GitHub-**hosted** Windows Actions runners are not an interactive console for WGC stills.
 *
 * Uses `RUNNER_ENVIRONMENT=github-hosted` (not merely `GITHUB_ACTIONS=true`) so interactive
 * self-hosted Windows runners keep native window capture.
 */
internal fun isNonInteractiveHostedWindows(
    osName: String = System.getProperty("os.name").orEmpty(),
    getenv: (String) -> String? = System::getenv,
): Boolean =
    osName.startsWith("Windows", ignoreCase = true) &&
        getenv("RUNNER_ENVIRONMENT") == "github-hosted"

/** Marker for classloader defaults (avoids referencing ComposeAutomator from this helper). */
private object VisualIdleSurfaceCapture

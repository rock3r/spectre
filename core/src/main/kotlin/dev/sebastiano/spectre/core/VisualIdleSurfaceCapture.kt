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
 * to screen-region capture (that would reintroduce occlusion sensitivity #355 exists to remove),
 * except when the native helper cannot actually run (#503: recording classes present but GStreamer
 * / platform helper missing). That case is treated as "native unavailable" and uses
 * [ScreenCaptureBackend.captureRegion], matching 0.4.0 Robot sampling.
 *
 * Returning `null` for other native failures makes the visual-idle streak reset so the wait times
 * out rather than reporting fake stability.
 *
 * When the native bridge is absent, disabled, or not actually usable, falls back to
 * [ScreenCaptureBackend.captureRegion].
 */
internal fun captureSurfaceForVisualIdle(
    backend: ScreenCaptureBackend,
    window: TrackedWindow,
    surfaceRegion: Rectangle,
    windowBounds: Rectangle,
    frameInsets: Insets,
    nativeWindowCaptureAvailable: Boolean,
): BufferedImage? {
    if (!nativeWindowCaptureAvailable) {
        return backend.captureRegion(surfaceRegion)
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
    } catch (_: UnsupportedOperationException) {
        null
    } catch (error: IllegalStateException) {
        if (isNativeCaptureHelperUnusable(error)) {
            backend.captureRegion(surfaceRegion)
        } else {
            null
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

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
            ) ?: return null
        hashes[i] = imageHash(image)
    }
    return hashes.contentHashCode()
}

/**
 * True when the recording-owned native still bridge is on the classpath and this host is allowed to
 * use it.
 *
 * Used by atomic [ComposeAutomator.capture]: class presence means take the native path and fail
 * closed if the helper cannot run. Do **not** treat a missing `gst-launch-1.0` as "use Robot
 * region" here — that would silently crop another application's pixels into an atomic artifact
 * (#355). GitHub Actions hosted Windows is still treated as non-interactive (WGC needs a console).
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
 * cannot run, [waitForVisualIdle] region-falls back like 0.4.0 instead of marking every sample
 * unsampleable. Atomic [ComposeAutomator.capture] uses [isNativeWindowCaptureBridgePresent] instead
 * so a missing helper stays fail-closed.
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
 * [captureSurfaceForVisualIdle] may region-fallback in this case (#503). Other native failures stay
 * unsampleable so #355's no-silent-region-substitute rule still holds.
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

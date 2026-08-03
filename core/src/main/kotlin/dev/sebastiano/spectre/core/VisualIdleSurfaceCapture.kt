@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.core.capture.cropImageToScreenRegion
import dev.sebastiano.spectre.core.capture.normalizeImageToScreenBounds
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * Captures one tracked Compose surface for visual-idle frame hashing.
 *
 * When [nativeWindowCaptureAvailable] is true, uses window-scoped pixels via
 * [ScreenCaptureBackend.captureWindow] and crops to [surfaceRegion]. Failures do **not** fall back
 * to screen-region capture (that would reintroduce occlusion sensitivity #355 exists to remove).
 * Returning `null` makes the visual-idle streak reset so the wait times out rather than reporting
 * fake stability.
 *
 * When the native bridge is absent or disabled, falls back to [ScreenCaptureBackend.captureRegion]
 * so visual-idle still works in environments without `spectre-recording` on the classpath.
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
    } catch (_: IllegalStateException) {
        null
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
 * True when window-scoped native stills can be used for visual-idle / atomic capture.
 *
 * Requires the recording-owned bridge on the classpath and a [RobotDriver] that allows platform
 * capture. GitHub Actions Windows runners are treated as non-interactive: Windows Graphics Capture
 * needs an interactive console there (same gate as Issue14 screenshot validation), so callers fall
 * back to region sampling instead of hanging on a one-shot WGC helper.
 */
internal fun isNativeWindowCaptureAvailable(
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

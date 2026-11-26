@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Rectangle
import kotlinx.coroutines.runInterruptible

/**
 * Samples tracked Compose surfaces without making [ComposeAutomator] own capture-policy details.
 */
internal class VisualIdleFrameSampler(
    private val refreshWindows: () -> Unit,
    private val trackedWindows: () -> List<TrackedWindow>,
    private val backend: ScreenCaptureBackend,
    private val runBoundedOnWorker: (Long, () -> Int?) -> Int?,
) {
    suspend fun sample(
        budgetMs: Long,
        captureScope: NativeCaptureWaitScope,
        allowScreenRegionFallback: Boolean,
    ): Int? = runInterruptible {
        runBoundedOnWorker(budgetMs) {
            refreshWindows()
            val surfaces = trackedComposeSurfaces()
            if (surfaces.isEmpty()) return@runBoundedOnWorker null

            val nativeCaptureAvailable =
                requireVisualIdleNativeCapture(
                    captureScope.unavailableReason(),
                    allowScreenRegionFallback,
                )
            val geometry = readOnEdt {
                surfaces.associate { (window, _) ->
                    window to (Rectangle(window.window.bounds) to windowInsets(window.window))
                }
            }
            hashTrackedSurfacesForVisualIdle(
                surfaces = surfaces,
                windowBoundsFor = { geometry.getValue(it).first },
                frameInsetsFor = { geometry.getValue(it).second },
                backend = backend,
                nativeWindowCaptureAvailable = nativeCaptureAvailable,
                allowScreenRegionFallback = allowScreenRegionFallback,
            )
        }
    }

    private fun trackedComposeSurfaces(): List<Pair<TrackedWindow, Rectangle>> = readOnEdt {
        trackedWindows().mapNotNull { window ->
            runCatching { window.composeSurfaceBoundsOnScreen }
                .getOrNull()
                ?.takeIf { !it.isEmpty }
                ?.let { window to it }
        }
    }
}

package dev.sebastiano.spectre.agent

/**
 * Resolved screenshot capture target for the agent/daemon/CLI path.
 *
 * Default is a tracked window (index 0 when the caller does not name one). Full-desktop capture is
 * never implied: callers must request [Fullscreen] explicitly. Window capture failure must surface
 * as an error rather than silently falling back to the full desktop.
 */
@ExperimentalSpectreAgentApi
public sealed interface ScreenshotTarget {
    /** Capture the tracked Compose surface at [windowIndex]. */
    public data class Window(public val windowIndex: Int) : ScreenshotTarget

    /** Capture the full virtual desktop framebuffer (explicit opt-in only). */
    public data object Fullscreen : ScreenshotTarget
}

/**
 * Pure resolution of screenshot options against a live window list.
 *
 * @param fullscreen when true, only [ScreenshotTarget.Fullscreen] is allowed; combining it with a
 *   window or surface target is an error.
 * @param windowIndex optional explicit index from `spectre windows` / `WindowSummaryDto.index`.
 * @param surfaceId optional explicit surface id from `WindowSummaryDto.surfaceId`.
 * @param windows ordered list of `(index, surfaceId)` for the attached session after refresh.
 * @return the resolved target, or a failure with a human-readable message.
 */
@ExperimentalSpectreAgentApi
public fun resolveScreenshotTarget(
    fullscreen: Boolean,
    windowIndex: Int?,
    surfaceId: String?,
    windows: List<Pair<Int, String>>,
): Result<ScreenshotTarget> =
    when {
        fullscreen -> resolveFullscreen(windowIndex, surfaceId)
        surfaceId != null -> resolveBySurface(surfaceId, windowIndex, windows)
        else -> resolveByIndex(windowIndex ?: 0, windows)
    }

@ExperimentalSpectreAgentApi
private fun resolveFullscreen(windowIndex: Int?, surfaceId: String?): Result<ScreenshotTarget> =
    if (windowIndex != null || surfaceId != null) {
        Result.failure(
            IllegalArgumentException(
                "fullscreen screenshot cannot be combined with --window or --surface"
            )
        )
    } else {
        Result.success(ScreenshotTarget.Fullscreen)
    }

@ExperimentalSpectreAgentApi
private fun resolveBySurface(
    surfaceId: String,
    windowIndex: Int?,
    windows: List<Pair<Int, String>>,
): Result<ScreenshotTarget> {
    val match = windows.firstOrNull { it.second == surfaceId }
    return when {
        match == null ->
            Result.failure(
                IllegalArgumentException(
                    "No tracked window with surfaceId=$surfaceId (have ${windows.size})"
                )
            )
        windowIndex != null && match.first != windowIndex ->
            Result.failure(
                IllegalArgumentException(
                    "surfaceId=$surfaceId is at windowIndex=${match.first}, not $windowIndex"
                )
            )
        else -> Result.success(ScreenshotTarget.Window(match.first))
    }
}

@ExperimentalSpectreAgentApi
private fun resolveByIndex(index: Int, windows: List<Pair<Int, String>>): Result<ScreenshotTarget> =
    when {
        windows.isEmpty() ->
            Result.failure(
                IllegalStateException(
                    "No tracked windows to screenshot; cannot fall back to full-desktop capture"
                )
            )
        windows.none { it.first == index } ->
            Result.failure(
                IllegalArgumentException("No tracked window at index $index (have ${windows.size})")
            )
        else -> Result.success(ScreenshotTarget.Window(index))
    }

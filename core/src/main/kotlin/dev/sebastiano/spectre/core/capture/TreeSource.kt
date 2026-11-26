package dev.sebastiano.spectre.core.capture

import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.TrackedWindow

/** Internal boundary between a live UI tree reader and the immutable capture mapping path. */
@OptIn(InternalSpectreApi::class)
internal fun interface TreeSource {
    fun snapshot(trackedWindows: List<TrackedWindow>): List<CaptureNodeSnapshot>
}

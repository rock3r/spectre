@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path

/**
 * Finds the live coordinator among the [CoordinatorSocketCandidates] for an endpoint.
 *
 * A path that a coordinator abandoned answers with an IO error rather than a clean refusal, so an
 * attempt that throws is treated as "not here" and the walk continues. Only one coordinator can be
 * alive at a time — the election lock in the server enforces that — so the first candidate that
 * completes a session is unambiguously the right one.
 */
@ExperimentalSpectreInputCoordinationApi
public object CoordinatorSocketDiscovery {
    /** Returns the first non-null result of [connect] over [candidates], or null if none answer. */
    public fun <T : Any> firstReachable(candidates: List<Path>, connect: (Path) -> T?): T? =
        candidates.firstNotNullOfOrNull { candidate ->
            runCatching { connect(candidate) }.getOrNull()
        }
}

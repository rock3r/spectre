@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path

/**
 * Finds the live coordinator among the [CoordinatorSocketCandidates] for an endpoint.
 *
 * Only one coordinator can be alive at a time — the election lock in the server enforces that — so
 * the first candidate that answers is unambiguously the right one.
 *
 * [firstReachable] deliberately catches nothing. "Nothing is listening here" is a *value*, not an
 * exception: [connect] returns null for it and the walk continues. Anything [connect] throws is a
 * real failure from a coordinator that did answer — a malformed or incompatible frame, an
 * interrupted wait — and must reach the caller unchanged rather than being downgraded into "no
 * coordinator found".
 */
@ExperimentalSpectreInputCoordinationApi
public object CoordinatorSocketDiscovery {
    /** Returns the first non-null result of [connect] over [candidates], or null if none answer. */
    public fun <T : Any> firstReachable(candidates: List<Path>, connect: (Path) -> T?): T? =
        candidates.firstNotNullOfOrNull(connect)
}

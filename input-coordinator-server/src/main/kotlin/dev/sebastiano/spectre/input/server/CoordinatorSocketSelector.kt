@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import java.io.IOException
import java.nio.file.Path

/** Outcome of trying to take ownership of one candidate socket path. */
internal sealed interface SocketBindAttempt<out T> {
    /** The path was free and is now bound to [value]. */
    data class Bound<T>(val path: Path, val value: T) : SocketBindAttempt<T>

    /** Another coordinator answered here; this desktop already has an owner. */
    data object LiveCoordinator : SocketBindAttempt<Nothing>

    /** The path is occupied by something dead that cannot be cleared or bound. */
    data class Unusable(val reason: String) : SocketBindAttempt<Nothing>
}

/** The candidate a coordinator ended up binding, and whatever the bind produced. */
internal data class SocketSelection<T>(val path: Path, val value: T)

/**
 * Picks the socket path a coordinator binds, walking [CoordinatorSocketCandidates] in order.
 *
 * Kept free of any real socket or filesystem work so the decision — in particular the single-owner
 * guarantee — is testable on every platform, including the Windows state that produced #462 and
 * cannot be reproduced elsewhere.
 */
internal object CoordinatorSocketSelector {
    /**
     * Returns the first candidate that binds.
     *
     * A [SocketBindAttempt.LiveCoordinator] anywhere in the list stops the walk and fails: falling
     * forward past a live owner onto a later generation would put two coordinators on one desktop,
     * which is the one thing this whole subsystem exists to prevent.
     */
    fun <T> select(
        candidates: List<Path>,
        attempt: (Path) -> SocketBindAttempt<T>,
    ): SocketSelection<T> {
        require(candidates.isNotEmpty()) { "At least one candidate socket path is required" }
        val rejected = mutableListOf<String>()
        for (candidate in candidates) {
            when (val outcome = attempt(candidate)) {
                is SocketBindAttempt.Bound ->
                    return SocketSelection(path = outcome.path, value = outcome.value)
                SocketBindAttempt.LiveCoordinator ->
                    throw IOException("A coordinator is already listening at $candidate")
                is SocketBindAttempt.Unusable -> rejected += "$candidate (${outcome.reason})"
            }
        }
        throw IOException(
            "Could not bind a coordinator socket. Every candidate path is occupied by something " +
                "that is neither reachable nor removable: ${rejected.joinToString("; ")}"
        )
    }
}

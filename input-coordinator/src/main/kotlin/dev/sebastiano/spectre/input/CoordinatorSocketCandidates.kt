@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * The ordered socket paths a coordinator may bind, and its clients may probe, for one endpoint.
 *
 * The canonical path is always first, so a healthy host behaves exactly as it did before: one
 * deterministic socket, reused run after run.
 *
 * The fallbacks exist because a socket path can stop being usable without becoming free. On Windows
 * hosts with a filesystem filter over the user profile, an AF_UNIX socket file created under
 * `%LOCALAPPDATA%`/`%APPDATA%` outlives both a clean `close()` and process exit, and can afterwards
 * be neither deleted, renamed, nor re-bound — `Files.exists` even reports `false` for it while a
 * directory listing still shows it. Without a fallback, one coordinator run left that machine
 * unable to start a coordinator ever again, which took the whole attach input path down with it
 * (#462).
 *
 * Both halves walk this same list in this same order, so no discovery handshake is needed: at most
 * one coordinator is alive at a time (the election lock guarantees that), and a dead path simply
 * refuses connections.
 */
@ExperimentalSpectreInputCoordinationApi
public object CoordinatorSocketCandidates {
    /** Total paths offered, canonical included. */
    public const val MAX_GENERATIONS: Int = 8

    /** Conservative portable ceiling for a `sockaddr_un` path, in encoded bytes. */
    public const val MAX_SOCKET_PATH_BYTES: Int = 100

    /**
     * Returns up to [limit] paths for [socketPath], canonical first.
     *
     * Fallbacks are generation-suffixed siblings (`name-1.sock`, `name-2.sock`, …). Any that would
     * exceed [MAX_SOCKET_PATH_BYTES] is dropped rather than offered, because a candidate the OS
     * would reject for length is worse than one that was never suggested. The canonical path is
     * always returned even when it is itself over the limit — the endpoint resolver validates it
     * and reports that far more usefully than an empty list would.
     */
    public fun candidates(socketPath: Path, limit: Int = MAX_GENERATIONS): List<Path> {
        require(limit > 0) { "limit must be positive, was $limit" }
        val fallbacks =
            (1 until limit).map { generation -> siblingForGeneration(socketPath, generation) }
        return listOf(socketPath) + fallbacks.filter { it.fitsSocketPathLimit() }
    }

    private fun siblingForGeneration(socketPath: Path, generation: Int): Path {
        val fileName = socketPath.fileName.toString()
        val extensionSeparator = fileName.lastIndexOf('.')
        val suffixed =
            if (extensionSeparator <= 0) {
                "$fileName-$generation"
            } else {
                fileName.substring(0, extensionSeparator) +
                    "-$generation" +
                    fileName.substring(extensionSeparator)
            }
        return socketPath.resolveSibling(suffixed)
    }

    private fun Path.fitsSocketPathLimit(): Boolean =
        toString().toByteArray(StandardCharsets.UTF_8).size <= MAX_SOCKET_PATH_BYTES
}

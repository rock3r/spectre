@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import java.nio.file.Path
import java.time.Duration

/** Runnable entrypoint for one local coordinator process. */
@ExperimentalSpectreInputCoordinationApi
public object CoordinatorProcessMain {
    /** Parses [arguments], binds the requested endpoint, and serves until shutdown. */
    @Throws(InterruptedException::class)
    public fun run(arguments: List<String>) {
        val socketPath = option(arguments, SOCKET_OPTION)?.let(Path::of) ?: error(USAGE)
        val idleMillis = option(arguments, IDLE_MILLIS_OPTION)?.toLongOrNull() ?: error(USAGE)
        require(idleMillis > 0) { USAGE }
        val endpoint = CoordinatorEndpoint(socketPath.parent, socketPath)
        LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMillis(idleMillis)).use { server
            ->
            server.start()
            server.awaitTermination()
        }
    }

    private fun option(arguments: List<String>, name: String): String? {
        val index = arguments.indexOf(name)
        return arguments.getOrNull(index + 1).takeIf { index >= 0 }
    }

    private const val SOCKET_OPTION: String = "--socket"
    private const val IDLE_MILLIS_OPTION: String = "--idle-millis"
    private const val USAGE: String =
        "Usage: spectre-input-coordinator --socket <path> --idle-millis <positive-ms>"
}

/** Starts the coordinator process entrypoint. */
@ExperimentalSpectreInputCoordinationApi
public fun main(arguments: Array<String>): Unit = CoordinatorProcessMain.run(arguments.asList())

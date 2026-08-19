package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.FrameLimits
import java.io.IOException
import java.nio.file.Path

/** Runnable entrypoint for one Spectre daemon process. */
@OptIn(ExperimentalSpectreAgentApi::class)
public object DaemonMain {
    /** Starts the daemon at the socket supplied by [arguments]. */
    @Throws(IOException::class, InterruptedException::class)
    public fun run(arguments: List<String>) {
        // Applied before the socket opens so every frame this daemon writes — and every budget it
        // forwards into an injected JVM — uses the configured value.
        maxFrameBytes(arguments)?.let(FrameLimits::configure)
        DaemonProcess(socketPath(arguments)).use { process -> process.runUntilShutdown() }
    }

    /** Extracts the socket location from the daemon-only command line. */
    public fun socketPath(arguments: List<String>): Path {
        val index = arguments.indexOf(SOCKET_OPTION)
        require(index >= 0 && index + 1 < arguments.size) { USAGE }
        return Path.of(arguments[index + 1])
    }

    /**
     * Extracts the optional IPC frame budget; `null` leaves the daemon on the default.
     *
     * @throws IllegalArgumentException when the option is present but not a usable size, so a typo
     *   fails the daemon at startup rather than silently reverting it to the default.
     */
    public fun maxFrameBytes(arguments: List<String>): Int? {
        val index = arguments.indexOf(MAX_FRAME_BYTES_OPTION)
        if (index < 0) return null
        require(index + 1 < arguments.size) { USAGE }
        val raw = arguments[index + 1]
        return requireNotNull(FrameLimits.parseMaxFrameBytes(raw)) {
            "$MAX_FRAME_BYTES_OPTION must be a positive size (e.g. 64MiB), got '$raw'"
        }
    }

    private const val SOCKET_OPTION: String = "--socket"
    private const val MAX_FRAME_BYTES_OPTION: String = "--max-frame-bytes"
    private const val USAGE: String =
        "Usage: spectre-daemon --socket <path> [--max-frame-bytes <size>]"
}

@Throws(IOException::class, InterruptedException::class)
public fun main(arguments: Array<String>): Unit = DaemonMain.run(arguments.asList())

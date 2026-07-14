package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.nio.file.Path

/** Runnable entrypoint for one Spectre daemon process. */
public object DaemonMain {
    /** Starts the daemon at the socket supplied by [arguments]. */
    @Throws(IOException::class, InterruptedException::class)
    public fun run(arguments: List<String>) {
        DaemonProcess(socketPath(arguments)).use { process -> process.runUntilShutdown() }
    }

    /** Extracts the socket location from the daemon-only command line. */
    public fun socketPath(arguments: List<String>): Path {
        require(arguments.size == 2 && arguments.first() == SOCKET_OPTION) {
            "Usage: spectre-daemon --socket <path>"
        }
        return Path.of(arguments[1])
    }

    private const val SOCKET_OPTION: String = "--socket"
}

@Throws(IOException::class, InterruptedException::class)
public fun main(arguments: Array<String>): Unit = DaemonMain.run(arguments.asList())

package dev.sebastiano.spectre.cli.daemon

import java.io.IOException

/** Connects to the daemon, starting it once when the endpoint is absent. */
public class DaemonStartupCoordinator(
    private val connect: () -> Unit,
    private val start: () -> Unit,
) {
    /** Connects immediately or starts the daemon before one retry. */
    @Throws(IOException::class)
    public fun connectOrStart() {
        try {
            connect()
        } catch (_: IOException) {
            start()
            connect()
        }
    }
}

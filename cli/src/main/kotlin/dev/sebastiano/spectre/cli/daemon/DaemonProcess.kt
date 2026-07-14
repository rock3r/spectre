package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.nio.file.Path

/** Owns the daemon server lifecycle for one long-lived daemon process. */
public class DaemonProcess
@Throws(IOException::class)
public constructor(
    private val socketPath: Path,
    private val serverFactory: (Path) -> DaemonServer = ::DaemonServer,
) : AutoCloseable {
    private val lifecycleLock: Any = Any()
    private var server: DaemonServer? = null
    private var closed: Boolean = false

    /** Blocks until the daemon receives a shutdown request or [close] is called. */
    @Throws(IOException::class, InterruptedException::class)
    public fun runUntilShutdown() {
        val activeServer =
            synchronized(lifecycleLock) {
                check(!closed) { "Daemon process is closed" }
                server ?: serverFactory(socketPath).also { server = it }
            }
        activeServer.awaitTermination(TERMINATION_WAIT_MILLIS)
    }

    /** Stops the daemon when the hosting process is asked to exit. */
    override fun close() {
        synchronized(lifecycleLock) {
                closed = true
                server
            }
            ?.close()
    }

    private companion object {
        private const val TERMINATION_WAIT_MILLIS: Long = Long.MAX_VALUE
    }
}

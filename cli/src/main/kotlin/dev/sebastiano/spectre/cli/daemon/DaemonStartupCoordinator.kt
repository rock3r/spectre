package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.NoSuchFileException

/** Connects to the daemon, starting it once when the endpoint is absent. */
public class DaemonStartupCoordinator<T>(
    private val connect: () -> T,
    private val start: () -> Unit,
    private val onAbsent: () -> T? = { null },
) {
    /** Connects immediately or starts the daemon before waiting for its endpoint. */
    @Throws(IOException::class)
    public fun connectOrStart(): T {
        try {
            return connect()
        } catch (exception: IOException) {
            if (!isAbsentEndpoint(exception)) throw exception
            onAbsent()?.let {
                return it
            }
            return startOrConnectAfterRace()
        }
    }

    private fun startOrConnectAfterRace(): T {
        try {
            start()
        } catch (startFailure: IOException) {
            try {
                return connectUntilReady()
            } catch (connectFailure: IOException) {
                if (connectFailure.cause is InterruptedException) throw connectFailure
                startFailure.addSuppressed(connectFailure)
                throw startFailure
            }
        }
        return connectUntilReady()
    }

    private fun connectUntilReady(): T {
        repeat(MAXIMUM_STARTUP_CONNECTION_ATTEMPTS - 1) {
            try {
                return connect()
            } catch (exception: IOException) {
                if (!isAbsentEndpoint(exception)) throw exception
                waitForEndpoint()
            }
        }
        return connect()
    }

    private fun waitForEndpoint() {
        try {
            Thread.sleep(STARTUP_RETRY_DELAY_MILLIS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for the daemon endpoint", exception)
        }
    }

    private fun isAbsentEndpoint(exception: IOException): Boolean =
        exception is ConnectException ||
            exception is NoSuchFileException ||
            exception is DaemonConnectionClosedException ||
            (exception is SocketException &&
                exception.message in
                    setOf(MISSING_UNIX_SOCKET_MESSAGE, MISSING_WINDOWS_SOCKET_MESSAGE))

    private companion object {
        private const val MISSING_UNIX_SOCKET_MESSAGE: String = "No such file or directory"
        private const val MISSING_WINDOWS_SOCKET_MESSAGE: String =
            "The system cannot find the file specified"
        private const val MAXIMUM_STARTUP_CONNECTION_ATTEMPTS: Int = 500
        private const val STARTUP_RETRY_DELAY_MILLIS: Long = 20
    }
}

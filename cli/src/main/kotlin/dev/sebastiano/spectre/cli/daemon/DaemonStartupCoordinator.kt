package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.NoSuchFileException

/** Connects to the daemon, starting it once when the endpoint is absent. */
public class DaemonStartupCoordinator<T>(
    private val connect: () -> T,
    private val start: () -> Unit,
) {
    /** Connects immediately or starts the daemon before waiting for its endpoint. */
    @Throws(IOException::class)
    public fun connectOrStart(): T {
        try {
            return connect()
        } catch (exception: IOException) {
            if (!isAbsentEndpoint(exception)) throw exception
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
            (exception is SocketException && exception.message == MISSING_UNIX_SOCKET_MESSAGE)

    private companion object {
        private const val MISSING_UNIX_SOCKET_MESSAGE: String = "No such file or directory"
        private const val MAXIMUM_STARTUP_CONNECTION_ATTEMPTS: Int = 100
        private const val STARTUP_RETRY_DELAY_MILLIS: Long = 10
    }
}

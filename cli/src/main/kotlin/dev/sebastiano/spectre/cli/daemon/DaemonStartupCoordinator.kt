package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.NoSuchFileException

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
        } catch (exception: IOException) {
            if (!isAbsentEndpoint(exception)) throw exception
            startOrConnectAfterRace()
        }
    }

    private fun startOrConnectAfterRace() {
        try {
            start()
        } catch (startFailure: IOException) {
            try {
                connect()
                return
            } catch (connectFailure: IOException) {
                startFailure.addSuppressed(connectFailure)
                throw startFailure
            }
        }
        connect()
    }

    private fun isAbsentEndpoint(exception: IOException): Boolean =
        exception is ConnectException ||
            exception is NoSuchFileException ||
            (exception is SocketException && exception.message == MISSING_UNIX_SOCKET_MESSAGE)

    private companion object {
        private const val MISSING_UNIX_SOCKET_MESSAGE: String = "No such file or directory"
    }
}

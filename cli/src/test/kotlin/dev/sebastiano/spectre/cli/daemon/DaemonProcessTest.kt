package dev.sebastiano.spectre.cli.daemon

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DaemonProcessTest {
    @Test
    fun `close before run prevents starting a daemon`() {
        val socketPath = temporarySocketPath()
        val daemon = DaemonProcess(socketPath)
        daemon.close()
        val runner = Thread(daemon::runUntilShutdown)

        try {
            runner.start()
            runner.join(2_000)

            assertFalse(runner.isAlive)
            assertFalse(Files.exists(socketPath))
        } finally {
            daemon.close()
            runner.join(2_000)
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `runs the daemon until a protocol shutdown request`() {
        val socketPath = temporarySocketPath()
        val daemon = DaemonProcess(socketPath)
        val runner = Thread(daemon::runUntilShutdown)

        try {
            runner.start()
            awaitSocket(socketPath)

            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                DaemonWireCodec.writeRequest(
                    output,
                    DaemonRequest.Hello(DaemonProtocol.CurrentVersion),
                )
                assertEquals(
                    DaemonResponse.Hello(DaemonProtocol.CurrentVersion),
                    DaemonWireCodec.readResponse(input),
                )
                DaemonWireCodec.writeRequest(output, DaemonRequest.Shutdown)
                assertEquals(DaemonResponse.ShuttingDown, DaemonWireCodec.readResponse(input))
            }

            runner.join(2_000)
            assertFalse(runner.isAlive)
            assertFalse(Files.exists(socketPath))
        } finally {
            daemon.close()
            runner.join(2_000)
            deleteTemporarySocketPath(socketPath)
        }
    }
}

private fun awaitSocket(socketPath: Path) {
    repeat(MAX_SOCKET_WAIT_ATTEMPTS) {
        if (Files.exists(socketPath)) return
        Thread.sleep(SOCKET_WAIT_MILLIS)
    }
    error("Timed out waiting for daemon socket $socketPath")
}

private fun temporarySocketPath(): Path =
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Path.of("/tmp", "sp-d-${UUID.randomUUID().toString().take(8)}", "daemon", "daemon.sock")
    } else {
        Files.createTempDirectory("spectre-daemon-test").resolve("daemon").resolve("daemon.sock")
    }

private fun deleteTemporarySocketPath(socketPath: Path) {
    Files.deleteIfExists(socketPath)
    Files.deleteIfExists(socketPath.parent)
    Files.deleteIfExists(socketPath.parent.parent)
}

private const val MAX_SOCKET_WAIT_ATTEMPTS: Int = 100
private const val SOCKET_WAIT_MILLIS: Long = 10

package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonStartupCoordinatorTest {
    @Test
    fun `starts once and retries when the daemon is absent`() {
        var attempts = 0
        var starts = 0

        DaemonStartupCoordinator(
                connect = {
                    attempts++
                    if (attempts == 1) throw ConnectException("missing")
                },
                start = { starts++ },
            )
            .connectOrStart()

        assertEquals(2, attempts)
        assertEquals(1, starts)
    }

    @Test
    fun `starts once and retries when the socket path is absent`() {
        var attempts = 0
        var starts = 0

        DaemonStartupCoordinator(
                connect = {
                    attempts++
                    if (attempts == 1) throw NoSuchFileException("daemon.sock")
                },
                start = { starts++ },
            )
            .connectOrStart()

        assertEquals(2, attempts)
        assertEquals(1, starts)
    }

    @Test
    fun `starts once when Unix socket connection reports a missing path`() {
        var attempts = 0
        var starts = 0

        DaemonStartupCoordinator(
                connect = {
                    attempts++
                    if (attempts == 1) throw SocketException("No such file or directory")
                },
                start = { starts++ },
            )
            .connectOrStart()

        assertEquals(2, attempts)
        assertEquals(1, starts)
    }

    @Test
    fun `does not start for a daemon protocol failure`() {
        var starts = 0

        assertFailsWith<IOException> {
            DaemonStartupCoordinator(
                    connect = { throw IOException("Daemon handshake failed") },
                    start = { starts++ },
                )
                .connectOrStart()
        }

        assertEquals(0, starts)
    }

    @Test
    fun `does not start for a non-absence socket failure`() {
        var starts = 0

        assertFailsWith<SocketException> {
            DaemonStartupCoordinator(
                    connect = { throw SocketException("Connection reset") },
                    start = { starts++ },
                )
                .connectOrStart()
        }

        assertEquals(0, starts)
    }

    @Test
    fun `retries connect when startup races with another client`() {
        var attempts = 0
        var starts = 0

        DaemonStartupCoordinator(
                connect = {
                    attempts++
                    if (attempts == 1) throw ConnectException("missing")
                },
                start = {
                    starts++
                    throw DaemonAlreadyRunningException(java.nio.file.Path.of("daemon.sock"))
                },
            )
            .connectOrStart()

        assertEquals(2, attempts)
        assertEquals(1, starts)
    }
}

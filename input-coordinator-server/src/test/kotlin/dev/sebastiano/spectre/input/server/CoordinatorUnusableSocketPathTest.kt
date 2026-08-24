@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.CoordinatorSocketCandidates
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import java.io.IOException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * #462 end-to-end shape, portably.
 *
 * On the affected Windows hosts the canonical socket path becomes permanently unbindable and
 * undeletable. That exact state cannot be produced on Linux or macOS, so this test reproduces its
 * *observable* shape — a path that exists, hosts no coordinator, and cannot be cleared away — by
 * putting a non-empty directory where the socket belongs. `Files.delete` refuses it and `bind`
 * refuses it, which is precisely the situation that used to abort startup.
 */
class CoordinatorUnusableSocketPathTest {

    private val resources = mutableListOf<AutoCloseable>()
    private val temporaryDirectory: Path = Files.createTempDirectory("spc-462-")
    private val socketPath: Path = temporaryDirectory.resolve("input-v1-test.sock")
    private val endpoint =
        CoordinatorEndpoint(directory = temporaryDirectory, socketPath = socketPath)
    private val resource = DesktopResourceKey("user:501/windows-console")

    @AfterTest
    fun cleanUp() {
        resources.asReversed().forEach { runCatching { it.close() } }
        runCatching { Files.walk(temporaryDirectory).sorted(Comparator.reverseOrder()) }
            .getOrNull()
            ?.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    /** Makes [path] exist, host no listener, and resist `Files.delete`. */
    private fun blockPath(path: Path) {
        Files.createDirectory(path)
        Files.createFile(path.resolve("occupant"))
    }

    @Test
    fun `a coordinator still starts when the canonical socket path cannot be reclaimed`() {
        blockPath(socketPath)
        val server = LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1))
        resources += server

        server.start()

        assertEquals(
            CoordinatorSocketCandidates.candidates(socketPath)[1],
            server.boundSocketPath,
            "the server should have fallen forward to the next generation",
        )
        assertTrue(Files.exists(server.boundSocketPath), "the fallback socket should be bound")
    }

    @Test
    fun `a client reaches a coordinator that fell forward to a fallback path`() {
        blockPath(socketPath)
        val server = LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1))
        resources += server
        server.start()

        // Clients are handed the canonical endpoint; discovery is their job, not the caller's.
        // The canonical path here is a directory, so completing a session at all proves the client
        // walked past it to the generation the server actually bound.
        val client = LocalInputCoordinatorClient.connect(endpoint, resource, ownerLabel = "probe")
        resources += client

        assertTrue(client.coordinatorEpoch.isNotBlank(), "the session should be fully open")
        assertNotEquals(socketPath, server.boundSocketPath)
    }

    @Test
    fun `two coordinators still cannot both own the desktop`() {
        blockPath(socketPath)
        val first = LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1))
        val second = LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1))
        resources += first
        resources += second
        first.start()

        // The election lock is what guarantees single ownership; falling forward must not create a
        // second way in. Same-JVM contention surfaces as OverlappingFileLockException, matching
        // `LocalCoordinatorServerTest`; the point here is that it still fails.
        assertFailsWith<OverlappingFileLockException> { second.start() }
        assertEquals(
            endpoint.socketPath,
            second.boundSocketPath,
            "a rejected contender must not have bound any candidate",
        )
    }

    @Test
    fun `startup fails with a clear error when every candidate is unusable`() {
        CoordinatorSocketCandidates.candidates(socketPath).forEach(::blockPath)
        val server = LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1))
        resources += server

        val failure = assertFailsWith<IOException> { server.start() }

        assertTrue(
            failure.message.orEmpty().contains(socketPath.toString()),
            "the failure should name the canonical path: ${failure.message}",
        )
    }
}

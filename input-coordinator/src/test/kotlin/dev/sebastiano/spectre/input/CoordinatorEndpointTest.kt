@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class CoordinatorEndpointTest {

    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `endpoint name depends on effective user identity but not launcher environment`() {
        val first =
            CoordinatorEndpointResolver.resolve(
                baseDirectory = Path.of("/tmp"),
                effectiveUserId = "501",
            )
        val second =
            CoordinatorEndpointResolver.resolve(
                baseDirectory = Path.of("/tmp"),
                effectiveUserId = "501",
            )

        assertEquals(first, second)
        assertTrue(first.socketPath.fileName.toString().startsWith("input-v1-"))
        assertFalse(first.socketPath.toString().contains("501"))
    }

    @Test
    fun `UDS path length is rejected before bind or connect`() {
        val longBase = temporaryDirectory.resolve("x".repeat(120))

        val failure =
            assertFailsWith<IOException> {
                CoordinatorEndpointResolver.resolve(longBase, effectiveUserId = "501")
            }

        assertTrue(failure.message.orEmpty().contains("Unix-domain socket path"))
    }

    @Test
    fun `owner-only directory is created with private POSIX permissions`() {
        val endpoint =
            CoordinatorEndpoint(
                directory = temporaryDirectory.resolve("coordinator"),
                socketPath = temporaryDirectory.resolve("coordinator/input.sock"),
            )
        val protection = OwnerOnlyEndpointProtection.forPath(endpoint.socketPath)

        protection.prepareDirectory(endpoint.socketPath)

        if (Files.getFileStore(endpoint.directory).supportsFileAttributeView("posix")) {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(endpoint.directory),
            )
        }
    }

    @Test
    fun `symbolic-link endpoint directory is rejected without following it`() {
        val target = temporaryDirectory.resolve("target")
        Files.createDirectory(target)
        val link = temporaryDirectory.resolve("spectre-input-link")
        try {
            link.createSymbolicLinkPointingTo(target)
        } catch (_: UnsupportedOperationException) {
            return
        }
        val socket = link.resolve("input.sock")

        val failure =
            assertFailsWith<IOException> {
                OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)
            }

        assertTrue(failure.message.orEmpty().contains("symbolic link"))
    }

    @Test
    fun `existing broad POSIX directory is rejected instead of silently chmodded`() {
        val endpoint =
            CoordinatorEndpoint(
                directory = temporaryDirectory.resolve("coordinator"),
                socketPath = temporaryDirectory.resolve("coordinator/input.sock"),
            )
        Files.createDirectory(endpoint.directory)
        if (!Files.getFileStore(endpoint.directory).supportsFileAttributeView("posix")) return
        Files.setPosixFilePermissions(
            endpoint.directory,
            PosixFilePermissions.fromString("rwxrwxrwx"),
        )

        val failure =
            assertFailsWith<IOException> {
                OwnerOnlyEndpointProtection.forPath(endpoint.socketPath)
                    .prepareDirectory(endpoint.socketPath)
            }

        assertTrue(failure.message.orEmpty().contains("owner-only"))
    }
}

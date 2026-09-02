@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.file.FileSystemException
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
    fun `default POSIX endpoint uses the process owner identity`() {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val expected =
            CoordinatorEndpointResolver.resolve(
                baseDirectory = Path.of("/tmp"),
                effectiveUserId = requireNotNull(ProcessHandle.current().info().user().orElse(null)),
            )

        assertEquals(expected, LocalCoordinatorEnvironment.defaultEndpoint())
    }

    @Test
    fun `default endpoint and desktop key ignore the mutable JVM username`() {
        val originalUserName = System.getProperty("user.name")
        val expectedEndpoint = LocalCoordinatorEnvironment.defaultEndpoint()
        val expectedResource = LocalCoordinatorEnvironment.defaultDesktopResourceKey()
        try {
            System.setProperty("user.name", "isolated-jvm-user")

            assertEquals(expectedEndpoint, LocalCoordinatorEnvironment.defaultEndpoint())
            assertEquals(expectedResource, LocalCoordinatorEnvironment.defaultDesktopResourceKey())
        } finally {
            System.setProperty("user.name", originalUserName)
        }
    }

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
    fun `a missing fixed parent is created before the endpoint directory`() {
        // #462: the Windows base is %LOCALAPPDATA%\Temp, which is absent on profiles where the
        // user's temp directory has been redirected. prepareDirectory used createDirectory, which
        // throws NoSuchFileException when the parent is missing, so coordination was simply
        // unavailable on those otherwise valid configurations.
        val absentBase = temporaryDirectory.resolve("Temp")
        val socket = absentBase.resolve("spectre-input-abcd1234").resolve("input-v1-abcd1234.sock")

        OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)

        assertTrue(Files.isDirectory(absentBase), "the fixed Temp parent should have been created")
        assertTrue(Files.isDirectory(socket.parent), "the endpoint directory should exist")
    }

    @Test
    fun `symbolic-link endpoint directory is rejected without following it`() {
        val target = temporaryDirectory.resolve("target")
        Files.createDirectory(target)
        val link = temporaryDirectory.resolve("spectre-input-link")
        // Both branches mean "this host cannot make a symlink, so there is nothing to guard
        // against here". UnsupportedOperationException is a file system that has no symlinks at
        // all; FileSystemException is a host that has them but refuses this process the right to
        // create one -- on Windows that is the usual case, since createSymbolicLink needs
        // Developer Mode or SeCreateSymbolicLinkPrivilege and otherwise fails with "A required
        // privilege is not held by the client". Failing there would make `./gradlew check`, the
        // documented pre-push gate, impossible to get green on an ordinary Windows checkout. The
        // assertion below is unchanged, so hosts that can create symlinks still cover the guard.
        try {
            link.createSymbolicLinkPointingTo(target)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: FileSystemException) {
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

    @Test
    fun `an endpoint whose directory is not the socket's parent is rejected`() {
        // The owner-only guard protects the socket's parent, but the election lock and recovery
        // ledger live in `directory`. If the two could differ, a caller-supplied endpoint could
        // route coordinator state through a directory nothing checked.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CoordinatorEndpoint(
                    directory = temporaryDirectory.resolve("elsewhere"),
                    socketPath = temporaryDirectory.resolve("coordinator").resolve("input.sock"),
                )
            }

        assertTrue(failure.message.orEmpty().contains("parent"), failure.message)
    }

    @Test
    fun `an endpoint whose directory is the socket's parent is accepted`() {
        val directory = temporaryDirectory.resolve("coordinator")

        val endpoint = CoordinatorEndpoint(directory, directory.resolve("input.sock"))

        assertEquals(directory, endpoint.socketPath.parent)
    }
}

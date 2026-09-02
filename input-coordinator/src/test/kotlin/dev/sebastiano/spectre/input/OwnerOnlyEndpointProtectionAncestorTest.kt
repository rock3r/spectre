@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * #465: `prepareDirectory` guarded the endpoint directory and its immediate parent, but nothing
 * above them. A caller-supplied endpoint whose base directory does not exist yet is only
 * normalised, never canonicalised, so a symbolic link planted higher up — in a shared `/tmp`, say —
 * was followed silently and the election lock, recovery ledger, and socket all landed inside the
 * link's target while still appearing to satisfy the owner-only contract.
 *
 * The obvious remedy, rejecting every symlinked ancestor, cannot be applied literally: on macOS
 * `/tmp`, `/var`, and `/etc` are themselves symbolic links into `/private`, and JUnit's `@TempDir`
 * lives under `/var/folders`. Those root-level aliases are the one exemption, and the only one.
 */
class OwnerOnlyEndpointProtectionAncestorTest {

    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `a symbolic link above a missing base is rejected instead of followed`() {
        assumeCanCreateSymbolicLinks()
        val target = Files.createDirectory(temporaryDirectory.resolve("target"))
        val link = temporaryDirectory.resolve("link").createSymbolicLinkPointingTo(target)
        // "base" does not exist, which is exactly the shape CoordinatorEndpointResolver only
        // normalises. The link sits two levels above the endpoint directory, one above the
        // immediate parent that was already checked.
        val socket = link.resolve("base").resolve(ENDPOINT_DIRECTORY_NAME).resolve("input.sock")

        val failure =
            assertFailsWith<IOException> {
                OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)
            }

        assertTrue(failure.message.orEmpty().contains("symbolic link"), failure.message)
        assertFalse(
            Files.exists(target.resolve("base")),
            "nothing may be created through the link before it is rejected",
        )
    }

    @Test
    fun `a symbolic link above an existing endpoint directory is rejected`() {
        assumeCanCreateSymbolicLinks()
        val target = temporaryDirectory.resolve("target")
        val existing =
            Files.createDirectories(target.resolve("base").resolve(ENDPOINT_DIRECTORY_NAME))
        if (Files.getFileStore(existing).supportsFileAttributeView("posix")) {
            // Owner-only, so the only thing left to object to is the ancestor.
            Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwx------"))
        }
        val link = temporaryDirectory.resolve("link").createSymbolicLinkPointingTo(target)
        val socket = link.resolve("base").resolve(ENDPOINT_DIRECTORY_NAME).resolve("input.sock")

        val failure =
            assertFailsWith<IOException> {
                OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)
            }

        assertTrue(failure.message.orEmpty().contains("symbolic link"), failure.message)
    }

    @Test
    fun `a symbolic link as the immediate parent is still rejected`() {
        assumeCanCreateSymbolicLinks()
        val target = Files.createDirectory(temporaryDirectory.resolve("target"))
        val link = temporaryDirectory.resolve("link").createSymbolicLinkPointingTo(target)
        val socket = link.resolve(ENDPOINT_DIRECTORY_NAME).resolve("input.sock")

        val failure =
            assertFailsWith<IOException> {
                OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)
            }

        assertTrue(failure.message.orEmpty().contains("symbolic link"), failure.message)
        assertFalse(Files.exists(target.resolve(ENDPOINT_DIRECTORY_NAME)))
    }

    @Test
    fun `a missing base several levels deep is created beneath real ancestors`() {
        val socket =
            temporaryDirectory
                .resolve("missing")
                .resolve("base")
                .resolve(ENDPOINT_DIRECTORY_NAME)
                .resolve("input.sock")

        OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)

        assertTrue(Files.isDirectory(socket.parent), "the endpoint directory should exist")
        if (Files.getFileStore(socket.parent).supportsFileAttributeView("posix")) {
            assertTrue(
                Files.getPosixFilePermissions(socket.parent) ==
                    PosixFilePermissions.fromString("rwx------"),
                "the endpoint directory must stay owner-only",
            )
        }
    }

    @Test
    fun `an intermediate component occupied by a regular file is rejected`() {
        Files.createFile(temporaryDirectory.resolve("base"))
        val socket =
            temporaryDirectory
                .resolve("base")
                .resolve(ENDPOINT_DIRECTORY_NAME)
                .resolve("input.sock")

        assertFailsWith<IOException> {
            OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)
        }
    }

    @Test
    fun `the macOS tmp alias keeps a caller-supplied tmp endpoint working`() {
        val tmp = Path.of("/tmp")
        assumeTrue(
            Files.isSymbolicLink(tmp),
            "only meaningful where /tmp is an alias into /private, which is the macOS layout",
        )
        // A base under /tmp that does not exist yet: resolve() would only normalise it, so the
        // /tmp link stays in the path handed to prepareDirectory. This must keep working.
        val base = tmp.resolve("spectre-465-${UUID.randomUUID().toString().take(8)}")
        val socket = base.resolve(ENDPOINT_DIRECTORY_NAME).resolve("input.sock")
        try {
            OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)

            assertTrue(Files.isDirectory(socket.parent), "the endpoint directory should exist")
        } finally {
            Files.deleteIfExists(socket.parent)
            Files.deleteIfExists(base)
        }
    }

    @Test
    fun `a root-level link into private is a trusted alias`() {
        assumeCanCreateSymbolicLinks()
        Files.createDirectories(temporaryDirectory.resolve("private").resolve("tmp"))
        val alias =
            temporaryDirectory
                .resolve("tmp")
                .createSymbolicLinkPointingTo(Path.of("private", "tmp"))

        assertTrue(isTrustedPrivateAlias(alias, root = temporaryDirectory))
    }

    @Test
    fun `links that are not the root-level private alias are not trusted`() {
        assumeCanCreateSymbolicLinks()
        Files.createDirectories(temporaryDirectory.resolve("private").resolve("tmp"))
        Files.createDirectories(temporaryDirectory.resolve("elsewhere"))
        Files.createDirectories(temporaryDirectory.resolve("sub").resolve("private").resolve("tmp"))
        val pointingElsewhere =
            temporaryDirectory.resolve("tmp").createSymbolicLinkPointingTo(Path.of("elsewhere"))
        val differentName =
            temporaryDirectory
                .resolve("var")
                .createSymbolicLinkPointingTo(Path.of("private", "tmp"))
        val belowTheRoot =
            temporaryDirectory
                .resolve("sub")
                .resolve("tmp")
                .createSymbolicLinkPointingTo(Path.of("private", "tmp"))
        val plainDirectory = temporaryDirectory.resolve("private")

        assertFalse(isTrustedPrivateAlias(pointingElsewhere, root = temporaryDirectory))
        assertFalse(isTrustedPrivateAlias(differentName, root = temporaryDirectory))
        assertFalse(isTrustedPrivateAlias(belowTheRoot, root = temporaryDirectory))
        assertFalse(isTrustedPrivateAlias(plainDirectory, root = temporaryDirectory))
    }

    /**
     * Windows needs Developer Mode or `SeCreateSymbolicLinkPrivilege` to create a symbolic link and
     * otherwise throws [FileSystemException], not [UnsupportedOperationException] (#467). Skip
     * cleanly there rather than fail `./gradlew check`; hosts that can create links still cover the
     * guard.
     */
    private fun assumeCanCreateSymbolicLinks() {
        val probe = temporaryDirectory.resolve("symlink-probe")
        val canCreate =
            try {
                probe.createSymbolicLinkPointingTo(temporaryDirectory.resolve("probe-target"))
                Files.deleteIfExists(probe)
                true
            } catch (_: UnsupportedOperationException) {
                false
            } catch (_: FileSystemException) {
                false
            }
        assumeTrue(
            canCreate,
            "Host cannot create symbolic links (Windows: enable Developer Mode or grant " +
                "SeCreateSymbolicLinkPrivilege); the ancestor guards stay covered on hosts that can.",
        )
    }

    private companion object {
        const val ENDPOINT_DIRECTORY_NAME: String = "spectre-input-abcd1234"
    }
}

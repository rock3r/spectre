@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * #487: validating each ancestor immediately before descending into it (#483) is not the same as
 * descending atomically. A lexical walk re-resolves the whole path string from the root on every
 * `java.nio.file` call, so an ancestor accepted a moment ago can be replaced with a symbolic link
 * before the next call resolves through it.
 *
 * These tests stage exactly that substitution, at the one instant that matters, through the
 * [prepareEndpointDirectory] seam. They are the evidence for #487: a walk that resolves each
 * component once, relative to a directory it already holds open, cannot be redirected by a swap
 * that lands after the component was accepted. They fail against the lexical walk, which follows
 * the planted link and creates the endpoint inside the attacker's tree without complaint.
 *
 * What they do not show is that no window remains at all. `SecureDirectoryStream` has no `mkdirat`,
 * so a missing component is still created through its absolute path; see [prepareEndpointDirectory]
 * for what that costs and what still holds when it is lost.
 */
class OwnerOnlyEndpointProtectionDescentTest {

    @TempDir lateinit var temporaryDirectory: Path

    @Test
    fun `an ancestor swapped after it was accepted cannot redirect the descent`() {
        assumeSecureDirectoryStreams()
        assumeCanCreateSymbolicLinks()
        val intermediate = Files.createDirectory(temporaryDirectory.resolve("mid"))
        Files.createDirectory(intermediate.resolve("base"))
        val attackerTree = Files.createDirectory(temporaryDirectory.resolve("attacker"))
        val decoy = Files.createDirectory(attackerTree.resolve("base"))
        val relocated = temporaryDirectory.resolve("relocated-mid")
        val directory = intermediate.resolve("base").resolve(ENDPOINT_DIRECTORY_NAME)

        // Captured rather than asserted inline, so the assertion that matters -- that nothing
        // landed in the attacker's tree -- is evaluated even when the walk completes happily.
        val outcome = runCatching {
            prepareEndpointDirectory(directory) { accepted ->
                // The instant "mid" has been accepted and before its child is resolved: move
                // the real directory aside and leave a link to the attacker's tree in its
                // place. A lexical walk re-resolves "mid/base" from the root and lands in the
                // decoy; a descent holding "mid" open cannot be moved off it.
                if (accepted.fileName?.toString() == "mid" && !Files.isSymbolicLink(intermediate)) {
                    Files.move(intermediate, relocated)
                    intermediate.createSymbolicLinkPointingTo(attackerTree)
                }
            }
        }

        assertFalse(
            Files.exists(decoy.resolve(ENDPOINT_DIRECTORY_NAME), NOFOLLOW_LINKS),
            "the endpoint must not be created through the substituted ancestor",
        )
        assertFalse(
            Files.exists(
                relocated.resolve("base").resolve(ENDPOINT_DIRECTORY_NAME),
                NOFOLLOW_LINKS,
            ),
            "a substitution mid-descent must fail closed rather than create anything",
        )
        val failure =
            assertIs<IOException>(outcome.exceptionOrNull(), "the descent must fail closed")
        assertTrue(failure.message.orEmpty().contains("replaced"), failure.message)
    }

    @Test
    fun `the endpoint directory is not created through a parent swapped after it was accepted`() {
        assumeSecureDirectoryStreams()
        assumeCanCreateSymbolicLinks()
        val base = Files.createDirectory(temporaryDirectory.resolve("base"))
        val attackerTree = Files.createDirectory(temporaryDirectory.resolve("attacker"))
        val relocated = temporaryDirectory.resolve("relocated-base")
        val directory = base.resolve(ENDPOINT_DIRECTORY_NAME)

        val outcome = runCatching {
            prepareEndpointDirectory(directory) { accepted ->
                // Same substitution one level lower, where the endpoint directory itself is
                // what gets created through the link rather than a further ancestor.
                if (accepted.fileName?.toString() == "base" && !Files.isSymbolicLink(base)) {
                    Files.move(base, relocated)
                    base.createSymbolicLinkPointingTo(attackerTree)
                }
            }
        }

        assertFalse(
            Files.exists(attackerTree.resolve(ENDPOINT_DIRECTORY_NAME), NOFOLLOW_LINKS),
            "the endpoint must not be created inside the attacker's tree",
        )
        assertFalse(
            Files.exists(relocated.resolve(ENDPOINT_DIRECTORY_NAME), NOFOLLOW_LINKS),
            "a substitution mid-descent must fail closed rather than create anything",
        )
        val failure =
            assertIs<IOException>(outcome.exceptionOrNull(), "the descent must fail closed")
        assertTrue(failure.message.orEmpty().contains("replaced"), failure.message)
    }

    @Test
    fun `a filesystem-root endpoint directory is rejected`() {
        assumePosixFilesystem()
        // `/input.sock` makes the socket's parent the filesystem root. The descent opens `/` and
        // then has zero components left, so without an explicit check it would return without
        // ever applying the owner-only invariant the previous lexical walk enforced on `/`.
        val socket = Path.of("/input.sock")
        assertEquals(0, socket.toAbsolutePath().parent.nameCount)

        val failure =
            assertFailsWith<IOException> {
                OwnerOnlyEndpointProtection.forPath(socket).prepareDirectory(socket)
            }

        assertTrue(failure.message.orEmpty().contains("filesystem root"), failure.message)
    }

    @Test
    fun `the lexical fallback still enforces the endpoint contract`() {
        // macOS never gets a SecureDirectoryStream: the JDK sets SUPPORTS_OPENAT only when all six
        // *at functions resolve, and futimesat is not looked up under _ALLBSD_SOURCE, so
        // Files.newDirectoryStream returns a plain UnixDirectoryStream there. The descent falls
        // back to the lexical walk rather than failing, and that fallback has to keep the
        // guarantees the walk had before #487. Driven directly because a Linux host cannot produce
        // a non-secure stream to reach it through the front door.
        assumePosixFilesystem()
        val socket =
            temporaryDirectory
                .resolve("missing")
                .resolve("base")
                .resolve(ENDPOINT_DIRECTORY_NAME)
                .resolve("input.sock")

        prepareEndpointDirectoryLexically(socket.parent)

        assertTrue(Files.isDirectory(socket.parent), "the endpoint directory should exist")
        assertEquals(
            PosixFilePermissions.fromString("rwx------"),
            Files.getPosixFilePermissions(socket.parent),
            "the endpoint directory must be owner-only",
        )
    }

    @Test
    fun `the lexical fallback rejects a symbolic link above the endpoint`() {
        assumePosixFilesystem()
        assumeCanCreateSymbolicLinks()
        val target = Files.createDirectory(temporaryDirectory.resolve("target"))
        val link = temporaryDirectory.resolve("link").createSymbolicLinkPointingTo(target)
        val directory = link.resolve("base").resolve(ENDPOINT_DIRECTORY_NAME)

        val failure = assertFailsWith<IOException> { prepareEndpointDirectoryLexically(directory) }

        assertTrue(failure.message.orEmpty().contains("symbolic link"), failure.message)
        assertFalse(
            Files.exists(target.resolve("base")),
            "nothing may be created through the link before it is rejected",
        )
    }

    /**
     * [prepareEndpointDirectory] is the POSIX preparation; Windows keeps the lexical walk in
     * `BasicOwnerOnlyEndpointProtection` and has no `openat` to descend with.
     */
    private fun assumeSecureDirectoryStreams() {
        assumePosixFilesystem()
        val secure =
            Files.newDirectoryStream(temporaryDirectory).use { it is SecureDirectoryStream<*> }
        assumeTrue(
            secure,
            "the openat descent needs SecureDirectoryStream; macOS never reports SUPPORTS_OPENAT " +
                "(futimesat is not resolved under _ALLBSD_SOURCE) and falls back to the lexical walk",
        )
    }

    private fun assumePosixFilesystem() {
        assumeTrue(
            Files.getFileStore(temporaryDirectory).supportsFileAttributeView("posix"),
            "the openat-style descent is the POSIX path",
        )
    }

    /** See the identical helper in [OwnerOnlyEndpointProtectionAncestorTest] (#467). */
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
                "SeCreateSymbolicLinkPrivilege); the descent stays covered on hosts that can.",
        )
    }

    private companion object {
        const val ENDPOINT_DIRECTORY_NAME: String = "spectre-input-abcd1234"
    }
}

@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Canonical per-user directory and Unix-domain socket used by the local coordinator.
 *
 * [directory] must be the parent of [socketPath]. The socket's parent is the directory
 * [OwnerOnlyEndpointProtection.prepareDirectory] guards and creates, while the election lock and
 * the recovery ledger are placed in [directory]; if the two could diverge, those two files would
 * land in a directory nothing ever checked.
 */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorEndpoint(public val directory: Path, public val socketPath: Path) {
    init {
        // Lexical on purpose, so `..` is not folded away: the OS resolves it against whatever
        // precedes it at the time, so a spelling that only normalises to the socket's parent can
        // still name a different directory once a symbolic link sits in between.
        require(socketPath.toAbsolutePath().parent == directory.toAbsolutePath()) {
            "Coordinator directory $directory must be the parent of socket $socketPath"
        }
    }
}

/** Resolves a short endpoint that is independent of the launching process's environment. */
@ExperimentalSpectreInputCoordinationApi
public object CoordinatorEndpointResolver {
    /** Creates the canonical endpoint names without touching the filesystem. */
    @Throws(IOException::class)
    public fun resolve(baseDirectory: Path, effectiveUserId: String): CoordinatorEndpoint {
        require(effectiveUserId.isNotBlank()) { "Effective user ID must not be blank" }
        val canonicalBase =
            if (Files.exists(baseDirectory)) baseDirectory.toRealPath()
            else baseDirectory.normalize()
        val userHash = stableHash(effectiveUserId)
        val directory = canonicalBase.resolve("spectre-input-$userHash")
        val socketPath = directory.resolve("input-v1-${userHash.take(SOCKET_HASH_LENGTH)}.sock")
        val encodedLength = socketPath.toString().toByteArray(StandardCharsets.UTF_8).size
        if (encodedLength > CoordinatorSocketCandidates.MAX_SOCKET_PATH_BYTES) {
            throw IOException(
                "Unix-domain socket path is $encodedLength bytes; the safe maximum is " +
                    "${CoordinatorSocketCandidates.MAX_SOCKET_PATH_BYTES}: $socketPath"
            )
        }
        return CoordinatorEndpoint(directory = directory, socketPath = socketPath)
    }

    private fun stableHash(value: String): String =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.toByteArray(StandardCharsets.UTF_8)),
                0,
                HASH_BYTES,
            )

    private const val HASH_BYTES: Int = 8
    private const val SOCKET_HASH_LENGTH: Int = 8
}

/** Enforces the same-user trust boundary for a coordinator endpoint and its socket file. */
@ExperimentalSpectreInputCoordinationApi
public sealed interface OwnerOnlyEndpointProtection {
    /** Creates or validates the socket's parent directory without following a substituted link. */
    @Throws(IOException::class) public fun prepareDirectory(socketPath: Path)

    /** Applies owner-only access to a socket after it has been bound. */
    @Throws(IOException::class) public fun protectSocket(socketPath: Path)

    public companion object {
        /** Selects protection from the filesystem that will contain [path]. */
        public fun forPath(path: Path): OwnerOnlyEndpointProtection {
            val existingAncestor =
                generateSequence(path.toAbsolutePath()) { it.parent }.first(Files::exists)
            return if (Files.getFileStore(existingAncestor).supportsFileAttributeView("posix")) {
                PosixOwnerOnlyEndpointProtection
            } else {
                BasicOwnerOnlyEndpointProtection
            }
        }
    }
}

private object PosixOwnerOnlyEndpointProtection : OwnerOnlyEndpointProtection {
    override fun prepareDirectory(socketPath: Path) {
        val directory = socketPath.toAbsolutePath().parent
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            rejectSubstitutedDirectory(directory)
            val permissions = Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS)
            if (permissions != OWNER_ONLY_DIRECTORY_PERMISSIONS) {
                throw IOException("Existing coordinator directory $directory must be owner-only")
            }
        } else {
            rejectSymbolicParent(directory.parent)
            val ownerOnly =
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    OWNER_ONLY_DIRECTORY_PERMISSIONS
                )
            // Parents may legitimately be missing (#462), but only they are created loosely. The
            // endpoint directory itself still goes through the atomic createDirectory: on a
            // world-writable /tmp another user could plant a symlink between the exists() check
            // above and this call, and createDirectories would happily adopt a symlink whose
            // target is a directory, putting the lock, ledger, and socket under their control.
            directory.parent?.let { Files.createDirectories(it, ownerOnly) }
            Files.createDirectory(directory, ownerOnly)
        }
    }

    override fun protectSocket(socketPath: Path) {
        if (Files.isSymbolicLink(socketPath)) {
            throw IOException("Coordinator socket $socketPath must not be a symbolic link")
        }
        Files.setPosixFilePermissions(socketPath, OWNER_ONLY_SOCKET_PERMISSIONS)
    }

    private val OWNER_ONLY_DIRECTORY_PERMISSIONS: Set<PosixFilePermission> =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    private val OWNER_ONLY_SOCKET_PERMISSIONS: Set<PosixFilePermission> =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
}

private object BasicOwnerOnlyEndpointProtection : OwnerOnlyEndpointProtection {
    override fun prepareDirectory(socketPath: Path) {
        val directory = socketPath.toAbsolutePath().parent
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            rejectSubstitutedDirectory(directory)
        } else {
            rejectSymbolicParent(directory.parent)
            // Only the parents are created loosely: on Windows the base is %LOCALAPPDATA%\Temp,
            // which is absent on profiles whose temp directory has been redirected (#462). The
            // endpoint directory itself keeps the atomic createDirectory, which refuses to adopt
            // anything already sitting at that path, symlinks included.
            directory.parent?.let { Files.createDirectories(it) }
            Files.createDirectory(directory)
        }
    }

    override fun protectSocket(socketPath: Path) {
        if (Files.isSymbolicLink(socketPath)) {
            throw IOException("Coordinator socket $socketPath must not be a symbolic link")
        }
    }
}

private fun rejectSubstitutedDirectory(directory: Path) {
    if (Files.isSymbolicLink(directory)) {
        throw IOException("Coordinator directory $directory must not be a symbolic link")
    }
    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) {
        throw IOException("Coordinator directory $directory is not a directory")
    }
}

private fun rejectSymbolicParent(parent: Path?) {
    if (parent != null && Files.isSymbolicLink(parent)) {
        throw IOException("Coordinator parent $parent must not be a symbolic link")
    }
}

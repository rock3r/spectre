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

/** Canonical per-user directory and Unix-domain socket used by the local coordinator. */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorEndpoint(public val directory: Path, public val socketPath: Path)

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
            // createDirectories, not createDirectory: the configured base can legitimately not
            // exist yet, and failing there leaves coordination unavailable (#462). Any parent this
            // creates gets the same owner-only mode as the endpoint directory itself.
            Files.createDirectories(
                directory,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    OWNER_ONLY_DIRECTORY_PERMISSIONS
                ),
            )
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
            // createDirectories, not createDirectory: on Windows the base is %LOCALAPPDATA%\Temp,
            // which is absent on profiles whose temp directory has been redirected, and failing
            // there leaves coordination unavailable (#462). The directory stays inside the user
            // profile, so it inherits the owner-only ACL either way.
            Files.createDirectories(directory)
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

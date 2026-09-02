@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Canonical per-user directory and Unix-domain socket used by the local coordinator.
 *
 * [CoordinatorEndpointResolver.resolve] canonicalises an existing base, so the default endpoint
 * from [LocalCoordinatorEnvironment.defaultEndpoint] carries no symbolic link above it. A
 * caller-supplied endpoint must be an absolute path beneath a directory the caller already trusts:
 * [OwnerOnlyEndpointProtection.prepareDirectory] refuses a symbolic link at the endpoint directory
 * or at any ancestor of it, but it does not verify who owns those ancestors.
 */
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
    /**
     * Creates or validates the socket's parent directory without following a substituted link.
     *
     * Neither the endpoint directory nor any ancestor of it may be a symbolic link. The one
     * exemption is a root-level alias into `/private` — `/tmp`, `/var`, and `/etc` on macOS — which
     * only root can create. Missing components are created one at a time so a link planted while
     * this runs is rejected rather than adopted. Ownership of the existing ancestors is not
     * checked: a caller-supplied endpoint must sit beneath a directory the caller already trusts.
     */
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
        rejectSymbolicAncestors(directory)
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            rejectSubstituted(directory, role = "directory")
            val permissions = Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS)
            if (permissions != OWNER_ONLY_DIRECTORY_PERMISSIONS) {
                throw IOException("Existing coordinator directory $directory must be owner-only")
            }
        } else {
            val ownerOnly = PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS)
            // Parents may legitimately be missing (#462). Each of them, and the endpoint directory
            // itself, goes through the atomic createDirectory: on a world-writable /tmp another
            // user could plant a symlink between the ancestor walk above and this call, and
            // createDirectories would happily adopt a symlink whose target is a directory,
            // putting the lock, ledger, and socket under their control.
            createMissingParents(directory) { parent -> Files.createDirectory(parent, ownerOnly) }
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
        rejectSymbolicAncestors(directory)
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            rejectSubstituted(directory, role = "directory")
        } else {
            // Parents may be missing: on Windows the base is %LOCALAPPDATA%\Temp, which is absent
            // on profiles whose temp directory has been redirected (#462). Every component keeps
            // the atomic createDirectory, which refuses to adopt anything already sitting at that
            // path, symlinks included.
            createMissingParents(directory) { parent -> Files.createDirectory(parent) }
            Files.createDirectory(directory)
        }
    }

    override fun protectSocket(socketPath: Path) {
        if (Files.isSymbolicLink(socketPath)) {
            throw IOException("Coordinator socket $socketPath must not be a symbolic link")
        }
    }
}

/**
 * Refuses a symbolic link anywhere above [directory], except a root-level alias into `/private`.
 *
 * The walk is lexical and deliberately not normalised: the OS resolves a `..` that follows a
 * planted link against the link's target, so the link itself has to stay visible to this check.
 */
private fun rejectSymbolicAncestors(directory: Path) {
    val substituted =
        generateSequence(directory.parent) { it.parent }
            .firstOrNull { Files.isSymbolicLink(it) && !isTrustedPrivateAlias(it, it.root) }
    if (substituted != null) {
        throw IOException("Coordinator ancestor $substituted must not be a symbolic link")
    }
}

/**
 * Whether [link] is a symbolic link directly under [root] that points at `private/<same name>`: the
 * layout macOS uses for `/tmp`, `/var`, and `/etc`. Only root can create an entry directly under
 * the filesystem root, so another user cannot have planted such an alias. [root] is a parameter
 * only so tests can stage a fake root; production passes the real one.
 */
internal fun isTrustedPrivateAlias(link: Path, root: Path): Boolean {
    if (link.parent != root || !Files.isSymbolicLink(link)) return false
    val target = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return false
    return target == Path.of(PRIVATE_ALIAS_DIRECTORY).resolve(link.fileName)
}

/**
 * Creates every missing ancestor of [directory] with one atomic [createDirectory] each, outermost
 * first. A component that appears between the walk and its creation is accepted only if it is a
 * real directory: `createDirectories` would also adopt a symbolic link whose target is a directory,
 * which is exactly the substitution this file exists to refuse.
 */
private fun createMissingParents(directory: Path, createDirectory: (Path) -> Unit) {
    val missing =
        generateSequence(directory.parent) { it.parent }
            .takeWhile { !Files.exists(it, NOFOLLOW_LINKS) }
            .toList()
    missing.asReversed().forEach { parent ->
        try {
            createDirectory(parent)
        } catch (_: FileAlreadyExistsException) {
            rejectSubstituted(parent, role = "parent")
        }
    }
}

private fun rejectSubstituted(path: Path, role: String) {
    if (Files.isSymbolicLink(path)) {
        throw IOException("Coordinator $role $path must not be a symbolic link")
    }
    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
        throw IOException("Coordinator $role $path is not a directory")
    }
}

private const val PRIVATE_ALIAS_DIRECTORY: String = "private"

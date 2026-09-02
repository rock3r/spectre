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
 *
 * [directory] must be the parent of [socketPath]. The guard above protects the socket's parent,
 * while the coordinator keeps its election lock and recovery ledger in [directory]; letting the two
 * differ would route that state through a directory nothing checked.
 */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorEndpoint(public val directory: Path, public val socketPath: Path) {
    init {
        // Compared as absolute paths with `.` folded out. Absolute because prepareDirectory guards
        // socketPath.toAbsolutePath().parent, so that is the directory this has to agree with; and
        // `.` folded because it always names the directory it sits in, whatever symbolic links are
        // in the path, so two spellings that differ only by one already name the same place. `..`
        // is deliberately left where it is: the OS resolves it against whatever precedes it, so
        // collapsing it lexically is what would let a substituted link through -- which is also
        // why Path.normalize, which collapses both, cannot be used here.
        val socketParent = socketPath.toAbsolutePath().withoutDotSegments().parent
        require(socketParent == directory.toAbsolutePath().withoutDotSegments()) {
            "Coordinator endpoint directory $directory must be the socket's parent, " +
                "was ${socketPath.parent}"
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
        val ownerOnly = PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS)
        // Parents may legitimately be missing (#462); nothing is created when they all exist.
        prepareAncestors(directory) { ancestor -> Files.createDirectory(ancestor, ownerOnly) }
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            rejectSubstituted(directory, role = "directory")
            val permissions = Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS)
            if (permissions != OWNER_ONLY_DIRECTORY_PERMISSIONS) {
                throw IOException("Existing coordinator directory $directory must be owner-only")
            }
        } else {
            // The atomic createDirectory refuses to adopt anything already at this path, symlinks
            // included, so a link planted here loses the race rather than capturing the endpoint.
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
        // Parents may be missing: on Windows the base is %LOCALAPPDATA%\Temp, which is absent on
        // profiles whose temp directory has been redirected (#462).
        prepareAncestors(directory) { ancestor -> Files.createDirectory(ancestor) }
        if (Files.exists(directory, NOFOLLOW_LINKS)) {
            rejectSubstituted(directory, role = "directory")
        } else {
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
 * Validates every ancestor of [directory] top-down and creates the missing ones, one atomic
 * [createDirectory] each.
 *
 * Each component is checked immediately before it is descended into, rather than validating the
 * whole chain up front and then creating blindly. A link planted between those two passes used to
 * be invisible: the creation scan stopped at the newly existing component without looking at it,
 * and the endpoint directory's own `createDirectory` then resolved straight through it into the
 * attacker's target. The atomic creation only ever protected the final component, never the
 * traversal that reaches it.
 *
 * The walk is lexical and deliberately not normalised: the OS resolves a `..` that follows a
 * planted link against the link's target, so the link itself has to stay visible to this check. It
 * cannot make traversal race-free — only an `openat`-style descent could — but every component is
 * now rejected on sight instead of being skipped.
 */
private fun prepareAncestors(directory: Path, createDirectory: (Path) -> Unit) {
    val ancestors = generateSequence(directory.parent) { it.parent }.toList().asReversed()
    ancestors.forEach { ancestor ->
        if (Files.exists(ancestor, NOFOLLOW_LINKS)) {
            if (!isTrustedPrivateAlias(ancestor, ancestor.root)) {
                rejectSubstituted(ancestor, role = "ancestor")
            }
        } else {
            try {
                createDirectory(ancestor)
            } catch (_: FileAlreadyExistsException) {
                rejectSubstituted(ancestor, role = "ancestor")
            }
        }
    }
}

/**
 * Whether [link] is one of the root-level aliases macOS ships — `/tmp`, `/var`, and `/etc`, each
 * pointing at `private/<same name>` — and so may be descended into rather than refused.
 *
 * The exemption is deliberately narrow. It is a fixed list of names rather than a path shape, and
 * it applies on Darwin only: an ordinary Windows user with Developer Mode can create an entry at a
 * drive root, so a planted `C:\tmp -> private\tmp` would otherwise read as trusted. On macOS the
 * filesystem root is writable by root alone, which is what makes these aliases safe to follow.
 *
 * [root] and [macOs] are parameters only so tests can stage a fake root and both platforms;
 * production passes the real ones.
 */
internal fun isTrustedPrivateAlias(link: Path, root: Path, macOs: Boolean = isMacOs()): Boolean {
    if (!macOs || link.parent != root || !Files.isSymbolicLink(link)) return false
    val name = link.fileName.toString()
    if (name !in MACOS_ROOT_ALIASES) return false
    val target = runCatching { Files.readSymbolicLink(link) }.getOrNull() ?: return false
    return target == Path.of(PRIVATE_ALIAS_DIRECTORY).resolve(name)
}

private fun rejectSubstituted(path: Path, role: String) {
    if (Files.isSymbolicLink(path)) {
        throw IOException("Coordinator $role $path must not be a symbolic link")
    }
    if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
        throw IOException("Coordinator $role $path is not a directory")
    }
}

/**
 * Drops `.` name elements, leaving every other segment -- `..` included -- exactly where it was.
 *
 * [Path.normalize] is not usable here: it also collapses `..`, which the OS resolves against
 * whatever precedes it rather than lexically.
 */
private fun Path.withoutDotSegments(): Path {
    if (none { it.toString() == CURRENT_DIRECTORY_SEGMENT }) return this
    return fold(root ?: Path.of("")) { path, segment ->
        if (segment.toString() == CURRENT_DIRECTORY_SEGMENT) path else path.resolve(segment)
    }
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

private val MACOS_ROOT_ALIASES: Set<String> = setOf("tmp", "var", "etc")

private const val PRIVATE_ALIAS_DIRECTORY: String = "private"

private const val CURRENT_DIRECTORY_SEGMENT: String = "."

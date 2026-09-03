@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
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
     *
     * How much a concurrent substitution can do depends on the host. Where the JDK exposes an
     * `openat`-backed [SecureDirectoryStream] — Linux in practice — the components are walked with
     * a descent that resolves each of them exactly once ([prepareEndpointDirectory]). Where it does
     * not — Windows, and macOS, which never reports the capability — the walk is lexical and
     * re-resolves the path on every call, so a component accepted a moment ago can still be swapped
     * before the next call reaches through it (#487).
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
        prepareEndpointDirectory(socketPath.toAbsolutePath().parent)
    }

    override fun protectSocket(socketPath: Path) {
        if (Files.isSymbolicLink(socketPath)) {
            throw IOException("Coordinator socket $socketPath must not be a symbolic link")
        }
        Files.setPosixFilePermissions(socketPath, OWNER_ONLY_SOCKET_PERMISSIONS)
    }

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
 * Prepares the endpoint [directory] and every ancestor of it with an `openat`-style descent.
 *
 * Only where the platform has one. [SecureDirectoryStream] is not a POSIX guarantee: macOS never
 * reports the JDK's `SUPPORTS_OPENAT` capability, so it takes [prepareEndpointDirectoryLexically]
 * instead and keeps exactly the guarantees it had before #487. The hardening below is Linux's.
 *
 * Every component is opened relative to a descriptor for the component above it, never by
 * re-resolving the whole path from the root, and always with [NOFOLLOW_LINKS]. That is what a
 * lexical walk cannot do (#487): `java.nio.file` re-resolves the entire path string on every call,
 * so an ancestor accepted a moment ago could be replaced with a symbolic link before the next call
 * resolved through it, and the endpoint landed in the attacker's tree. A descriptor already open on
 * a directory keeps naming that directory no matter what happens to the name it was reached by, so
 * the substitution has nothing left to redirect.
 *
 * One step still resolves an absolute path: [SecureDirectoryStream] has no `mkdirat`, so a missing
 * component is created by [Files.createDirectory]. Its result is never trusted — the fd-relative
 * open that follows is what decides whether anything is adopted — and the file-key guard in
 * [createChildDirectory] fails the walk closed when the parent has been substituted since it was
 * accepted. What is left of the window is a directory created in a tree the caller did not intend;
 * it cannot become the endpoint.
 *
 * Missing parents are legitimate (#462): nothing is created when they all exist.
 *
 * A filesystem-root directory (`/` on POSIX) has no components to descend into, so it is rejected
 * rather than adopted: the walk would otherwise open the root, skip the owner-only check, and
 * return. The previous lexical walk rejected that shape because `/` is not mode 0700.
 *
 * After this function returns the descriptors are closed. Later path-based operations — election
 * lock, recovery ledger, socket bind — re-resolve from the root and are outside this walk's
 * guarantee. Pinning an endpoint descriptor through server startup is a follow-up, not the mkdirat
 * leftover #487 accepted.
 *
 * [afterDescendingInto] is a test seam, in the same spirit as [isTrustedPrivateAlias]'s `root` and
 * `macOs` parameters; production passes nothing. It fires with each component the descent has just
 * opened, which is the instant a test needs in order to stage the substitution this walk exists to
 * survive.
 */
internal fun prepareEndpointDirectory(directory: Path, afterDescendingInto: (Path) -> Unit = {}) {
    val absolute = directory.toAbsolutePath()
    val root =
        absolute.root ?: throw IOException("Coordinator directory $directory must be absolute")
    if (absolute.nameCount == 0) {
        throw IOException("Coordinator directory $directory must not be the filesystem root")
    }
    val lastIndex = absolute.nameCount - 1
    var current =
        openRootDirectory(root)
            ?: run {
                // No openat on this host (macOS); keep the pre-#487 guarantees rather than refuse.
                prepareEndpointDirectoryLexically(directory)
                return
            }
    var currentPath = root
    try {
        absolute.forEachIndexed { index, component ->
            val descended =
                current.descendInto(currentPath, component, isEndpoint = index == lastIndex)
            current.close()
            current = descended.stream
            currentPath = descended.path
            afterDescendingInto(currentPath)
        }
    } finally {
        current.close()
    }
}

/** A component the descent has opened, and the path that names it once aliases are followed. */
private class Descended(val stream: SecureDirectoryStream<Path>, val path: Path)

/**
 * Opens [component] relative to the directory this stream holds, creating it when it is missing.
 *
 * The fd-relative `readAttributes` only classifies the component so the failure can name what is
 * wrong with it; the `NOFOLLOW_LINKS` open below is what enforces the rule, and it would refuse a
 * link planted between the two.
 */
private fun SecureDirectoryStream<Path>.descendInto(
    parentPath: Path,
    component: Path,
    isEndpoint: Boolean,
): Descended {
    val role = if (isEndpoint) "directory" else "ancestor"
    val childPath = parentPath.resolve(component)
    val attributes = readAttributesOrNull(component)
    var created = false
    when {
        attributes == null -> created = createChildDirectory(parentPath, childPath, role)
        attributes.isSymbolicLink -> {
            if (isEndpoint || !isTrustedPrivateAlias(childPath, childPath.root)) {
                throw IOException("Coordinator $role $childPath must not be a symbolic link")
            }
            // A Darwin root alias. Following it through the descriptor is the exemption; only root
            // can rewrite an entry directly under /, so there is no unprivileged swap to lose here.
            val target = childPath.root.resolve(PRIVATE_ALIAS_DIRECTORY).resolve(component)
            return Descended(newDirectoryStream(component), target)
        }
        !attributes.isDirectory ->
            throw IOException("Coordinator $role $childPath is not a directory")
    }
    val stream = newDirectoryStream(component, NOFOLLOW_LINKS)
    var handedOver = false
    try {
        // A directory this walk created is owner-only by construction. One it merely adopted is
        // not, and "already existed" includes losing the create race to someone else.
        if (isEndpoint && !created) requireOwnerOnly(stream, childPath)
        handedOver = true
    } finally {
        if (!handedOver) stream.close()
    }
    return Descended(stream, childPath)
}

/**
 * Rejects an adopted endpoint directory that is not owner-only.
 *
 * The mode is read back from the open descriptor rather than from the path: this is the directory
 * the coordinator will actually use, whatever the path resolves to by now.
 */
private fun requireOwnerOnly(stream: SecureDirectoryStream<Path>, path: Path) {
    val permissions =
        stream
            .getFileAttributeView(PosixFileAttributeView::class.java)
            .readAttributes()
            .permissions()
    if (permissions != OWNER_ONLY_DIRECTORY_PERMISSIONS) {
        throw IOException("Existing coordinator directory $path must be owner-only")
    }
}

/**
 * Creates [childPath], which the descent found missing, leaving adoption to the caller's open.
 *
 * This is the one step that cannot be done relative to the descriptor, so it compares the open
 * directory's file key against the one [parentPath] resolves to now and refuses to create anything
 * when they differ — that is a parent substituted since the descent accepted it.
 *
 * Returns whether this walk is the one that created the directory; losing the race to a concurrent
 * creator is not an error, but the loser has adopted a directory it cannot vouch for.
 */
private fun SecureDirectoryStream<Path>.createChildDirectory(
    parentPath: Path,
    childPath: Path,
    role: String,
): Boolean {
    val openKey =
        getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey()
    val resolvedKey =
        runCatching {
                Files.readAttributes(parentPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                    .fileKey()
            }
            .getOrNull()
    if (openKey == null || openKey != resolvedKey) {
        throw IOException(
            "Coordinator $role $childPath cannot be created: $parentPath was replaced while the " +
                "endpoint directory was being prepared"
        )
    }
    return try {
        Files.createDirectory(
            childPath,
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS),
        )
        true
    } catch (_: FileAlreadyExistsException) {
        // Another process created it first, legitimately or not. The fd-relative open decides
        // whether it is usable, and the caller still has to check what it adopted.
        false
    }
}

private fun SecureDirectoryStream<Path>.readAttributesOrNull(
    component: Path
): PosixFileAttributes? =
    try {
        getFileAttributeView(component, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            .readAttributes()
    } catch (_: NoSuchFileException) {
        null
    }

/**
 * Opens the filesystem root, which is the only component the descent takes on trust.
 *
 * Fails closed when the provider does not hand back a [SecureDirectoryStream]: without one there is
 * no fd-relative open to descend with, and silently falling back to the lexical walk would leave
 * callers believing in a guarantee they no longer have. Every POSIX filesystem the coordinator
 * selects this protection for returns one.
 */
/**
 * Opens the filesystem root as a [SecureDirectoryStream], or null where the platform has none.
 *
 * Not every POSIX host has one. The JDK sets its `SUPPORTS_OPENAT` capability only when all six of
 * `openat`, `fstatat`, `unlinkat`, `renameat`, `futimesat` and `fdopendir` resolve, and it does not
 * even look `futimesat` up under `_ALLBSD_SOURCE`, so macOS always returns a plain
 * `UnixDirectoryStream` and the descent is in practice a Linux one. Null means the caller falls
 * back to [prepareEndpointDirectoryLexically] rather than failing: refusing to prepare an endpoint
 * at all would take the coordinator off Darwin entirely, which is a far worse outcome than keeping
 * the guarantees that platform already had.
 */
private fun openRootDirectory(root: Path): SecureDirectoryStream<Path>? {
    val stream = Files.newDirectoryStream(root)
    @Suppress("UNCHECKED_CAST") val secure = stream as? SecureDirectoryStream<Path>
    if (secure == null) stream.close()
    return secure
}

/**
 * Prepares [directory] the way the POSIX path did before #487, for hosts with no `openat` descent.
 *
 * Every component is still validated immediately before it is descended into, and the endpoint is
 * still owner-only, but the walk re-resolves the whole path on every call, so a component accepted
 * a moment earlier can be replaced before the next call reaches through it. That is the residual
 * window #487 closes on Linux and cannot close here.
 */
internal fun prepareEndpointDirectoryLexically(directory: Path) {
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

private val OWNER_ONLY_DIRECTORY_PERMISSIONS: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

/**
 * Validates every ancestor of [directory] top-down and creates the missing ones, one atomic
 * [createDirectory] each.
 *
 * This is the walk for hosts with no `openat` to descend with: Windows, whose
 * [Files.newDirectoryStream] returns a plain `DirectoryStream`, and — through
 * [prepareEndpointDirectoryLexically] — macOS, which never reports the JDK capability that backs
 * [SecureDirectoryStream]. Only Linux reaches [prepareEndpointDirectory]'s fd-relative descent.
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
 * cannot make traversal race-free (#487) — every `java.nio.file` call re-resolves the whole path
 * from the root, so a component accepted a moment ago can be replaced before the next call reaches
 * through it — but every component is rejected on sight instead of being skipped.
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

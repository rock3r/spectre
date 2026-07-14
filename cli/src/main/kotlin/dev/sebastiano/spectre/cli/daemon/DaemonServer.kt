package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.EnumSet
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Long-lived local daemon endpoint for the CLI/MCP protocol.
 *
 * The server accepts one request/response connection at a time. Automation sessions are inherently
 * serial, and the registry supplies deterministic lifecycle semantics for every connection.
 */
public class DaemonServer
@Throws(IOException::class)
public constructor(
    socketPath: Path,
    private val registry: DaemonSessionRegistry = DaemonSessionRegistry(),
) : AutoCloseable {
    private val socketPath: Path = socketPath.also { path ->
        require(path == path.normalize()) { "Daemon socket path must not contain dot segments" }
    }
    private val running: AtomicBoolean = AtomicBoolean(true)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val activityLock: Any = Any()
    private var lastActivityNanos: Long = System.nanoTime()
    private val activeClient: AtomicReference<SocketChannel?> = AtomicReference(null)
    private val socketProtection: DaemonSocketProtection =
        DaemonSocketProtection.forPath(socketPath)
    private val createdParents: List<Path> = socketProtection.createMissingParents(socketPath)
    private val recoveryLockPath: Path = daemonRecoveryLockPath(socketPath)
    private val recoveryLockProtection: DaemonSocketProtection =
        DaemonSocketProtection.forPath(recoveryLockPath)

    init {
        recoveryLockProtection.createMissingParents(recoveryLockPath, validateAncestor = false)
    }

    private val serverChannel: ServerSocketChannel =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { channel ->
            var bound = false
            var socketBound = false
            try {
                bindOrRecoverStaleSocket(channel)
                socketBound = true
                socketProtection.protectSocket(socketPath)
                bound = true
            } finally {
                if (!bound) {
                    // Preserve the original bind/protection error. Best-effort cleanup must not
                    // obscure the actionable failure or leak an opened native socket descriptor.
                    runCatching { channel.close() }
                    if (socketBound) runCatching { Files.deleteIfExists(socketPath) }
                    removeCreatedParents()
                }
            }
        }

    private val acceptThread: Thread =
        Thread(::acceptLoop, "spectre-daemon-accept").apply {
            isDaemon = true
            start()
        }

    /** The local socket path currently owned by this server. */
    public val listeningAt: Path
        get() = socketPath

    /** Waits for the accept thread to exit, returning false only when [timeoutMillis] elapses. */
    @Throws(InterruptedException::class)
    public fun awaitTermination(timeoutMillis: Long = DEFAULT_TERMINATION_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must be non-negative" }
        if (timeoutMillis == 0L) return !acceptThread.isAlive
        acceptThread.join(timeoutMillis)
        return !acceptThread.isAlive
    }

    /** Milliseconds elapsed since the most recently received daemon request. */
    public fun idleMillis(): Long = synchronized(activityLock) { idleMillisLocked() }

    /** Stops this server only when it remains inactive for at least [timeoutMillis]. */
    public fun closeIfIdle(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(activityLock) {
            if (
                activeClient.get() != null ||
                    registry.hasSessions ||
                    idleMillisLocked() < timeoutMillis
            ) {
                return false
            }
            close()
            return true
        }
    }

    private fun idleMillisLocked(): Long =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastActivityNanos)

    private fun acceptLoop() {
        while (running.get()) {
            val client =
                try {
                    serverChannel.accept() ?: return
                } catch (_: ClosedChannelException) {
                    return
                } catch (exception: IOException) {
                    if (running.get()) logConnectionFailure(exception)
                    return
                }
            val accepted =
                synchronized(activityLock) {
                    if (!running.get()) {
                        false
                    } else {
                        activeClient.set(client)
                        true
                    }
                }
            if (!accepted) {
                client.close()
                return
            }
            @Suppress("TooGenericExceptionCaught")
            try {
                handleConnection(client)
            } catch (exception: Exception) {
                if (running.get()) logConnectionFailure(exception)
            }
        }
    }

    private fun handleConnection(client: SocketChannel) {
        try {
            client.use { channel ->
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                var handshakeComplete = false
                while (running.get()) {
                    val request = DaemonWireCodec.readRequest(input) ?: return
                    synchronized(activityLock) { lastActivityNanos = System.nanoTime() }
                    val response = handleRequest(request, handshakeComplete)
                    if (request is DaemonRequest.Hello) {
                        handshakeComplete = response is DaemonResponse.Hello
                    }
                    val isShutdown = request is DaemonRequest.Shutdown && handshakeComplete
                    try {
                        DaemonWireCodec.writeResponse(output, response)
                    } finally {
                        // The registry transitions before the response is written. A disconnected
                        // client must not leave this process listening in that shut-down state.
                        if (isShutdown) close()
                    }
                    if (isShutdown) return
                }
            }
        } finally {
            synchronized(activityLock) { activeClient.compareAndSet(client, null) }
        }
    }

    private fun handleRequest(request: DaemonRequest, handshakeComplete: Boolean): DaemonResponse =
        when (request) {
            is DaemonRequest.Hello ->
                when (
                    DaemonProtocol.checkCompatibility(
                        request.clientVersion,
                        DaemonProtocol.CurrentVersion,
                    )
                ) {
                    VersionCompatibility.Compatible -> registry.handle(request)
                    else ->
                        DaemonResponse.Error(
                            code = DaemonErrorCode.ProtocolError,
                            message = "incompatible daemon protocol version",
                        )
                }
            else ->
                if (handshakeComplete) registry.handle(request)
                else {
                    DaemonResponse.Error(
                        code = DaemonErrorCode.ProtocolError,
                        message = "send a compatible Hello request before session commands",
                    )
                }
        }

    private fun logConnectionFailure(exception: Exception) {
        System.err.println(
            "[spectre-daemon] connection terminated " +
                "(${exception.javaClass.simpleName}): ${exception.message ?: "<no message>"}"
        )
    }

    private fun bindOrRecoverStaleSocket(channel: ServerSocketChannel) {
        try {
            channel.bind(UnixDomainSocketAddress.of(socketPath))
            return
        } catch (exception: IOException) {
            if (!Files.exists(socketPath)) throw exception
        }

        withStaleSocketRecoveryLock {
            // Another process may have recovered the stale path while this process waited for the
            // lock. Rebinding before the liveness probe avoids unlinking its live socket.
            try {
                channel.bind(UnixDomainSocketAddress.of(socketPath))
                return@withStaleSocketRecoveryLock
            } catch (exception: IOException) {
                if (!Files.exists(socketPath)) throw exception
            }

            if (isDaemonListening(socketPath)) throw DaemonAlreadyRunningException(socketPath)

            requireStaleSocket(socketPath)
            Files.deleteIfExists(socketPath)
            channel.bind(UnixDomainSocketAddress.of(socketPath))
        }
    }

    private inline fun withStaleSocketRecoveryLock(action: () -> Unit) {
        val jvmLock = staleSocketRecoveryLocks.computeIfAbsent(recoveryLockPath) { Any() }
        synchronized(jvmLock) {
            FileChannel.open(recoveryLockPath, CREATE, WRITE).use { channel ->
                channel.lock().use { action() }
            }
        }
    }

    private fun requireStaleSocket(path: Path) {
        if ("unix" in path.fileSystem.supportedFileAttributeViews()) {
            val mode = Files.getAttribute(path, "unix:mode", NOFOLLOW_LINKS) as Int
            if (mode and FILE_TYPE_MASK != UNIX_SOCKET_FILE_TYPE) {
                throw IOException("Refusing to replace non-socket daemon path $path")
            }
            return
        }

        // Windows exposes an AF_UNIX socket as an "other" filesystem entry, while it does not
        // expose Unix FIFO/device nodes at an ordinary Path. Keep this platform-specific fallback
        // so a crashed Windows daemon can recover its socket without weakening Unix's exact mode
        // check, which protects user-owned FIFOs and device nodes.
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!isWindows() || !attributes.isOther) {
            throw IOException("Refusing to replace non-socket daemon path $path")
        }
    }

    private fun isDaemonListening(path: Path): Boolean =
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(path))
                true
            }
        } catch (_: java.net.ConnectException) {
            false
        } catch (_: NoSuchFileException) {
            false
        }

    /** Stops accepting clients and attempts to remove the socket plus server-created parents. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { registry.close() }
        runCatching { activeClient.getAndSet(null)?.close() }
        val removedSocket =
            runCatching {
                    // Hold the same lock as stale recovery before closing the channel. Otherwise a
                    // successor can bind after close and before this server unlinks the path.
                    withStaleSocketRecoveryLock {
                        try {
                            serverChannel.close()
                        } finally {
                            // Keep the recovery lock until the delete has at least been attempted.
                            // Retrying after releasing the lock could unlink a successor daemon.
                            runCatching { Files.deleteIfExists(socketPath) }
                        }
                    }
                }
                .isSuccess
        if (!removedSocket) runCatching { serverChannel.close() }
        removeCreatedParents()
        acceptThread.interrupt()
    }

    private fun removeCreatedParents() {
        createdParents.forEach { parent -> runCatching { Files.deleteIfExists(parent) } }
    }

    private companion object {
        private const val DEFAULT_TERMINATION_TIMEOUT_MILLIS: Long = 5_000

        private fun isWindows(): Boolean =
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        private val staleSocketRecoveryLocks: ConcurrentHashMap<Path, Any> = ConcurrentHashMap()

        private fun daemonRecoveryLockPath(socketPath: Path): Path {
            val canonicalSocketPath =
                (socketPath.parent ?: Path.of("")).toRealPath().resolve(socketPath.fileName)
            val digest =
                HexFormat.of()
                    .formatHex(
                        MessageDigest.getInstance("SHA-256")
                            .digest(canonicalSocketPath.toString().toByteArray())
                    )
            return Path.of(System.getProperty("user.home"), ".spectre", "daemon-locks", digest)
        }
    }
}

/** Thrown instead of replacing an existing daemon that is still accepting local connections. */
public class DaemonAlreadyRunningException(socketPath: Path) :
    IOException("A Spectre daemon is already listening at $socketPath")

/**
 * Keeps the daemon's per-user UDS directory and socket private on POSIX and Windows filesystems.
 */
private sealed interface DaemonSocketProtection {
    @Throws(IOException::class)
    fun createMissingParents(socketPath: Path, validateAncestor: Boolean = true): List<Path> {
        val parent = (socketPath.parent ?: Path.of("")).toAbsolutePath().normalize()
        if (Files.exists(parent, NOFOLLOW_LINKS)) {
            rejectSymbolicLink(parent)
            parent.parent?.let { validateAncestorChain(it, validateAncestor) }
            validateExistingDirectory(parent)
            return emptyList()
        }
        val missingParents =
            generateSequence(parent) { path -> path.parent }
                .takeWhile { path -> !Files.exists(path, NOFOLLOW_LINKS) }
                .toList()
        missingParents.last().parent?.let { validateAncestorChain(it, validateAncestor) }
        val createdParents = mutableListOf<Path>()
        try {
            missingParents.asReversed().forEach { path ->
                try {
                    createProtectedDirectory(path)
                    createdParents.add(path)
                } catch (_: FileAlreadyExistsException) {
                    rejectSymbolicLink(path)
                    validateExistingDirectory(path)
                }
            }
        } catch (exception: IOException) {
            createdParents.asReversed().forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
            throw exception
        }
        return createdParents.asReversed()
    }

    @Throws(IOException::class) fun createProtectedDirectory(directory: Path)

    @Throws(IOException::class) fun validateExistingDirectory(directory: Path)

    private fun validateAncestorChain(ancestor: Path, validateAncestor: Boolean) {
        generateSequence(ancestor) { path -> path.parent }
            .forEach { path ->
                if (validateAncestor) {
                    validateExistingAncestor(path)
                } else {
                    if (!isTrustedTempAlias(path)) rejectSymbolicLink(path)
                }
            }
    }

    private fun validateExistingAncestor(directory: Path) {
        if (isTrustedTempAlias(directory)) return
        rejectSymbolicLink(directory)
        if ("unix" in directory.fileSystem.supportedFileAttributeViews()) {
            val mode = Files.getAttribute(directory, "unix:mode", NOFOLLOW_LINKS) as Int
            if (!isTrustedOwner(directory)) {
                throw IOException("Daemon socket ancestor $directory must be owned by root or user")
            }
            if (mode and STICKY_BIT != 0) return
            if (mode and GROUP_OR_OTHER_WRITE_BITS == 0) return
            throw IOException(
                "Daemon socket ancestor $directory must not be group or world writable"
            )
        }
    }

    private fun rejectSymbolicLink(path: Path) {
        if (Files.isSymbolicLink(path)) {
            throw IOException("Daemon socket directories must not be symbolic links: $path")
        }
    }

    private fun isTrustedTempAlias(path: Path): Boolean =
        path == Path.of("/tmp") &&
            runCatching { Files.readSymbolicLink(path) == Path.of("private/tmp") }
                .getOrDefault(false)

    private fun isTrustedOwner(directory: Path): Boolean {
        val owner = Files.getOwner(directory, NOFOLLOW_LINKS).name
        val currentUser = System.getProperty("user.name")
        return owner == "root" || owner == currentUser
    }

    @Throws(IOException::class) fun protectSocket(socketPath: Path)

    companion object {
        fun forPath(path: Path): DaemonSocketProtection =
            if ("posix" in path.fileSystem.supportedFileAttributeViews()) Posix else WindowsAcl
    }
}

private data object Posix : DaemonSocketProtection {
    override fun createProtectedDirectory(directory: Path) {
        Files.createDirectory(
            directory,
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS),
        )
    }

    override fun protectSocket(socketPath: Path) {
        Files.setPosixFilePermissions(socketPath, OWNER_ONLY_SOCKET_PERMISSIONS)
    }

    override fun validateExistingDirectory(directory: Path) {
        if (Files.getPosixFilePermissions(directory) != OWNER_ONLY_DIRECTORY_PERMISSIONS) {
            throw IOException("Existing daemon socket directory $directory must be owner-only")
        }
        val owner = Files.getOwner(directory, NOFOLLOW_LINKS).name
        val currentUser = System.getProperty("user.name")
        if (owner != "root" && owner != currentUser) {
            throw IOException(
                "Existing daemon socket directory $directory must be owned by root or user"
            )
        }
    }
}

private data object WindowsAcl : DaemonSocketProtection {
    override fun createProtectedDirectory(directory: Path) {
        Files.createDirectory(directory)
        try {
            setOwnerOnlyAcl(directory)
        } catch (exception: IOException) {
            runCatching { Files.deleteIfExists(directory) }
            throw exception
        }
    }

    override fun protectSocket(socketPath: Path) {
        setOwnerOnlyAcl(socketPath)
    }

    override fun validateExistingDirectory(directory: Path) {
        val view =
            Files.getFileAttributeView(directory, AclFileAttributeView::class.java)
                ?: throw IOException("Filesystem at $directory does not support ACLs")
        val owner = view.owner
        if (
            view.acl.isEmpty() ||
                view.acl.any { entry ->
                    entry.type() != AclEntryType.ALLOW ||
                        entry.principal() != owner ||
                        !entry.permissions().containsAll(ALL_ACL_PERMISSIONS)
                }
        ) {
            throw IOException("Existing daemon socket directory $directory must be owner-only")
        }
    }

    private fun setOwnerOnlyAcl(path: Path) {
        val view =
            Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                ?: throw IOException("Filesystem at $path does not support ACLs")
        val ownerOnly =
            AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(view.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
                .build()
        view.acl = listOf(ownerOnly)
    }
}

private val OWNER_ONLY_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
private val OWNER_ONLY_SOCKET_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
private val ALL_ACL_PERMISSIONS: Set<AclEntryPermission> =
    EnumSet.allOf(AclEntryPermission::class.java)
private const val FILE_TYPE_MASK: Int = 0xF000
private const val UNIX_SOCKET_FILE_TYPE: Int = 0xC000
private const val STICKY_BIT: Int = 0x200
private const val GROUP_OR_OTHER_WRITE_BITS: Int = 0x12

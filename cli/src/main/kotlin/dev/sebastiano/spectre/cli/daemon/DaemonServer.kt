package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumSet
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
    private val socketPath: Path,
    private val registry: DaemonSessionRegistry = DaemonSessionRegistry(),
) : AutoCloseable {
    private val running: AtomicBoolean = AtomicBoolean(true)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val activeClient: AtomicReference<SocketChannel?> = AtomicReference(null)
    private val socketProtection: DaemonSocketProtection =
        DaemonSocketProtection.forPath(socketPath)
    private val createdParent: Path? = socketProtection.createMissingParent(socketPath)

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
                    runCatching { createdParent?.let(Files::deleteIfExists) }
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
            @Suppress("TooGenericExceptionCaught")
            try {
                handleConnection(client)
            } catch (exception: Exception) {
                if (running.get()) logConnectionFailure(exception)
            }
        }
    }

    private fun handleConnection(client: SocketChannel) {
        activeClient.set(client)
        try {
            client.use { channel ->
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                while (running.get()) {
                    val request = DaemonWireCodec.readRequest(input) ?: return
                    val response = registry.handle(request)
                    val isShutdown = request is DaemonRequest.Shutdown
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
            activeClient.compareAndSet(client, null)
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

        if (isDaemonListening(socketPath)) throw DaemonAlreadyRunningException(socketPath)

        requireStaleSocket(socketPath)
        Files.deleteIfExists(socketPath)
        channel.bind(UnixDomainSocketAddress.of(socketPath))
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

    /**
     * Stops accepting clients and removes the socket plus any parent directory created by this
     * server.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        runCatching { activeClient.getAndSet(null)?.close() }
        runCatching { serverChannel.close() }
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { createdParent?.let(Files::deleteIfExists) }
        acceptThread.interrupt()
    }

    private companion object {
        private const val DEFAULT_TERMINATION_TIMEOUT_MILLIS: Long = 5_000

        private fun isWindows(): Boolean =
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
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
    fun createMissingParent(socketPath: Path): Path? {
        val parent = socketPath.parent ?: return null
        if (Files.exists(parent)) return null
        createProtectedDirectory(parent)
        return parent
    }

    @Throws(IOException::class) fun createProtectedDirectory(directory: Path)

    @Throws(IOException::class) fun protectSocket(socketPath: Path)

    companion object {
        fun forPath(path: Path): DaemonSocketProtection =
            if ("posix" in path.fileSystem.supportedFileAttributeViews()) Posix else WindowsAcl
    }
}

private data object Posix : DaemonSocketProtection {
    override fun createProtectedDirectory(directory: Path) {
        Files.createDirectories(
            directory,
            PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIRECTORY_PERMISSIONS),
        )
    }

    override fun protectSocket(socketPath: Path) {
        Files.setPosixFilePermissions(socketPath, OWNER_ONLY_SOCKET_PERMISSIONS)
    }
}

private data object WindowsAcl : DaemonSocketProtection {
    override fun createProtectedDirectory(directory: Path) {
        Files.createDirectories(directory)
        setOwnerOnlyAcl(directory)
    }

    override fun protectSocket(socketPath: Path) {
        setOwnerOnlyAcl(socketPath)
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
private const val FILE_TYPE_MASK: Int = 0xF000
private const val UNIX_SOCKET_FILE_TYPE: Int = 0xC000

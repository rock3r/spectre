package dev.sebastiano.spectre.agent

import java.io.IOException
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * POSIX HotSpot attach sockets at the well-known `.java_pid<pid>` path.
 *
 * The Linux/macOS attach provider treats that path as a ready listener: if the file exists it
 * connects and never creates `.attach_pid` or sends SIGQUIT. A leftover owner-only socket therefore
 * fails every `VirtualMachine.attach` with `Connection refused` until the path is removed. Windows
 * uses a named pipe and is a no-op here.
 */
internal object HotSpotAttachSocket {

    internal fun wellKnownPaths(pid: Long): List<Path> {
        val name = ".java_pid$pid"
        val paths = linkedSetOf<Path>()
        paths.add(Path.of("/tmp", name))
        val tmpdir = System.getProperty("java.io.tmpdir")?.trim().orEmpty()
        if (tmpdir.isNotEmpty()) {
            paths.add(Path.of(tmpdir).toAbsolutePath().normalize().resolve(name))
        }
        return paths.toList()
    }

    /**
     * After `VirtualMachine.attach` `Connection refused`, unlink a leftover `.java_pid<pid>` so the
     * next attach can create `.attach_pid` and SIGQUIT. Post-`loadAgent` `Connection refused` is
     * ignored. Live listeners are left in place ([clearStaleOrphan] probes connect first).
     */
    internal fun recoverStaleOrphanIfAttachRefused(
        pid: Long,
        message: String?,
        causeMessage: String?,
    ) {
        val msg = (message.orEmpty() + " " + causeMessage.orEmpty()).lowercase()
        if ("virtualmachine.attach(" in msg && "connection refused" in msg) {
            clearStaleOrphan(pid)
        }
    }

    /**
     * Unlinks a leftover well-known attach socket for [pid] when a connect is refused. Live
     * listeners, non-sockets, and unreadable paths are left untouched.
     *
     * @return true when at least one orphan path was removed
     */
    internal fun clearStaleOrphan(pid: Long): Boolean {
        if (pid <= 0L || isWindows()) return false
        var removed = false
        for (path in wellKnownPaths(pid)) {
            if (removeIfOrphanSocket(path)) {
                removed = true
            }
        }
        return removed
    }

    /**
     * Unlinks [path] only when it is a `.java_pid<digits>` Unix socket that refuses connect. A live
     * listener, a regular file, a symlink, or any other connect failure is left in place.
     */
    internal fun removeIfOrphanSocket(path: Path): Boolean {
        if (!isWellKnownAttachSocketName(path)) return false
        if (!isUnixDomainSocket(path)) return false
        if (!isConnectRefused(path)) return false
        return try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            false
        }
    }

    private fun isWellKnownAttachSocketName(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false
        if (!name.startsWith(SOCKET_PREFIX)) return false
        val digits = name.substring(SOCKET_PREFIX.length)
        return digits.isNotEmpty() && digits.all { it.isDigit() }
    }

    private fun isUnixDomainSocket(path: Path): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        if ("unix" !in path.fileSystem.supportedFileAttributeViews()) return false
        return try {
            val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
            mode and FILE_TYPE_MASK == UNIX_SOCKET_FILE_TYPE
        } catch (_: IOException) {
            false
        }
    }

    private fun isConnectRefused(path: Path): Boolean =
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(path))
                false
            }
        } catch (_: ConnectException) {
            true
        } catch (_: IOException) {
            false
        }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private const val SOCKET_PREFIX: String = ".java_pid"
    private const val FILE_TYPE_MASK: Int = 0xF000
    private const val UNIX_SOCKET_FILE_TYPE: Int = 0xC000
}

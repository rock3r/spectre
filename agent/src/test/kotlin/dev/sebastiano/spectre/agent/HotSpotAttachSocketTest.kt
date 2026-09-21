package dev.sebastiano.spectre.agent

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * POSIX coverage for leftover HotSpot `.java_pid<pid>` sockets.
 *
 * Linux `VirtualMachine.attach` treats an existing well-known path as a ready listener: it connects
 * and never creates `.attach_pid` / SIGQUIT. An owner-only orphan therefore fails every retry with
 * `Connection refused` until the path is removed (#526 Codex P1).
 *
 * Windows uses a named pipe, so this class is POSIX-only.
 */
@DisabledOnOs(OS.WINDOWS)
class HotSpotAttachSocketTest {

    @Test
    fun `well-known paths include tmp java_pid for the pid`() {
        val paths = HotSpotAttachSocket.wellKnownPaths(18421)
        assertTrue(
            paths.contains(Path.of("/tmp", ".java_pid18421")),
            "expected /tmp/.java_pid18421 in $paths",
        )
        assertTrue(
            paths.all { it.fileName.toString() == ".java_pid18421" },
            "every well-known path must be named .java_pid18421, got $paths",
        )
    }

    @Test
    fun `removeIfOrphanSocket deletes an owner-only orphan and leaves a live listener`() {
        val dir = Files.createTempDirectory("spectre-java-pid-orphan-")
        val orphan = dir.resolve(".java_pid1001")
        val live = dir.resolve(".java_pid1002")
        val regular = dir.resolve(".java_pid1003")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
                plantOrphanOwnerOnlySocket(orphan)
                listener.bind(UnixDomainSocketAddress.of(live))
                Files.writeString(regular, "not-a-socket")

                assertTrue(HotSpotAttachSocket.removeIfOrphanSocket(orphan))
                assertFalse(Files.exists(orphan))

                assertFalse(HotSpotAttachSocket.removeIfOrphanSocket(live))
                assertTrue(Files.exists(live, LinkOption.NOFOLLOW_LINKS))

                assertFalse(HotSpotAttachSocket.removeIfOrphanSocket(regular))
                assertTrue(Files.isRegularFile(regular))

                assertFalse(HotSpotAttachSocket.removeIfOrphanSocket(dir.resolve(".java_pid404")))
            }
        } finally {
            Files.deleteIfExists(orphan)
            Files.deleteIfExists(regular)
            Files.deleteIfExists(live)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `recover on attach Connection refused clears a well-known orphan`() {
        val pid = unusedPid()
        val socket = HotSpotAttachSocket.wellKnownPaths(pid).first()
        assumeTrue(socket.parent != null && Files.isDirectory(socket.parent), "need a writable tmp")
        try {
            plantOrphanOwnerOnlySocket(socket)
            HotSpotAttachSocket.recoverStaleOrphanIfAttachRefused(
                pid = pid,
                message = "VirtualMachine.attach($pid) failed: IOException: Connection refused",
                causeMessage = "Connection refused",
            )
            assertFalse(
                Files.exists(socket),
                "Connection refused + leftover .java_pid must unlink the orphan",
            )
        } finally {
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `recover ignores loadAgent Connection refused so a leftover socket stays`() {
        val pid = unusedPid()
        val socket = HotSpotAttachSocket.wellKnownPaths(pid).first()
        assumeTrue(socket.parent != null && Files.isDirectory(socket.parent), "need a writable tmp")
        try {
            plantOrphanOwnerOnlySocket(socket)
            HotSpotAttachSocket.recoverStaleOrphanIfAttachRefused(
                pid = pid,
                message =
                    "VirtualMachine.loadAgent(agent.jar) failed: IOException: Connection refused",
                causeMessage = "Connection refused",
            )
            assertTrue(
                Files.exists(socket),
                "post-loadAgent Connection refused must not unlink .java_pid",
            )
        } finally {
            Files.deleteIfExists(socket)
        }
    }

    @Test
    fun `clearing an orphan on a live JVM lets VirtualMachine attach trigger`() {
        spawnBareJvm().use { child ->
            val socket = Path.of("/tmp", ".java_pid${child.pid}")
            try {
                // Wait until vm_start has finished. HotSpot unlinks leftover .java_pid during
                // startup, so planting earlier is a race that either deletes the orphan or
                // lets attach take the SIGQUIT path and succeed.
                val attachReady = waitUntilAttachable(child.pid)
                assertTrue(child.isAlive, "stock idle JVM died before attach")
                assumeTrue(
                    attachReady.ok,
                    "child JVM pid=${child.pid} never became attachable: ${attachReady.error}",
                )
                plantOrphanOwnerOnlySocket(socket)
                assertTrue(Files.exists(socket, LinkOption.NOFOLLOW_LINKS), "planted $socket")
                assertFalse(isListeningUnixSocket(socket), "planted orphan must refuse connect")

                val first = attachOnly(child.pid)
                assertTrue(
                    isAttachConnectionRefused(first),
                    "orphaned 0600 .java_pid must refuse attach; got $first",
                )
                val retryWithoutClear = attachOnly(child.pid)
                assertTrue(
                    isAttachConnectionRefused(retryWithoutClear),
                    "retrying attach without unlink must keep failing; got $retryWithoutClear",
                )

                assertTrue(HotSpotAttachSocket.clearStaleOrphan(child.pid))
                assertFalse(Files.exists(socket), "clearStaleOrphan must remove $socket")

                val recovered = attachOnly(child.pid)
                assertEquals(
                    null,
                    recovered,
                    "attach after unlink must trigger the live JVM listener",
                )
            } finally {
                Files.deleteIfExists(socket)
            }
        }
    }

    /**
     * Stock JDK idle process with a one-class classpath. The full `:agent:test` classpath is large
     * enough that a child started from it can miss a 5s attach window during `./gradlew check`.
     */
    private fun spawnBareJvm(): BareJvm {
        val javaHome = Paths.get(System.getProperty("java.home"))
        val javaBin = javaHome.resolve("bin/java").toString()
        val javacBin = javaHome.resolve("bin/javac").toString()
        assumeTrue(
            Files.isRegularFile(Paths.get(javacBin)),
            "javac is required to spawn a stock idle JVM",
        )
        val dir = Files.createTempDirectory("spectre-idle-jvm-")
        val src = dir.resolve("SpectreIdleJvm.java")
        Files.writeString(
            src,
            """
            public class SpectreIdleJvm {
              public static void main(String[] args) throws Exception {
                Thread.sleep(Long.MAX_VALUE);
              }
            }
            """
                .trimIndent(),
        )
        val compile =
            ProcessBuilder(javacBin, src.toString())
                .redirectErrorStream(true)
                .directory(dir.toFile())
                .start()
        check(compile.waitFor(10, TimeUnit.SECONDS) && compile.exitValue() == 0) {
            "javac SpectreIdleJvm failed: ${compile.inputStream.bufferedReader().readText()}"
        }
        val process =
            ProcessBuilder(javaBin, "-Xmx32m", "-cp", dir.toString(), "SpectreIdleJvm")
                .redirectErrorStream(true)
                .start()
        var attempts = 0
        while (!process.isAlive && attempts < 40) {
            Thread.sleep(25)
            attempts++
        }
        check(process.isAlive) { "stock idle JVM exited immediately" }
        return BareJvm(process, dir)
    }

    private class BareJvm(private val process: Process, private val dir: Path) : AutoCloseable {
        val pid: Long = process.pid()
        val isAlive: Boolean
            get() = process.isAlive

        override fun close() {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            dir.toFile().deleteRecursively()
        }
    }
}

private fun plantOrphanOwnerOnlySocket(path: Path) {
    Files.createDirectories(path.parent)
    Files.deleteIfExists(path)
    val ownerOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
        server.bind(UnixDomainSocketAddress.of(path))
        Files.setPosixFilePermissions(path, ownerOnly)
    }
    check(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "failed to plant orphan at $path" }
    Files.setPosixFilePermissions(path, ownerOnly)
}

private data class AttachProbe(val ok: Boolean, val error: Throwable? = null)

private fun waitUntilAttachable(pid: Long, timeoutMs: Long = 15_000): AttachProbe {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var lastError: Throwable? = null
    while (System.nanoTime() < deadline) {
        lastError = attachOnly(pid)
        if (lastError == null) return AttachProbe(ok = true)
        Thread.sleep(50)
    }
    return AttachProbe(ok = lastError == null, error = lastError)
}

private fun unusedPid(): Long {
    repeat(16) {
        val candidate = UNUSED_PID_FLOOR + ThreadLocalRandom.current().nextLong(0, 1_000_000)
        if (ProcessHandle.of(candidate).isEmpty) return candidate
    }
    error("could not allocate an unused pid for a well-known attach socket")
}

private fun isAttachConnectionRefused(error: Throwable?): Boolean =
    error is java.io.IOException &&
        error.message.orEmpty().contains("Connection refused", ignoreCase = true)

private fun isListeningUnixSocket(path: Path): Boolean {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
    return try {
        java.nio.channels.SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path))
            true
        }
    } catch (_: java.io.IOException) {
        false
    }
}

/**
 * Reflective [com.sun.tools.attach.VirtualMachine.attach] so this suite does not compile-depend on
 * `jdk.attach`. Returns the attach failure, or null on success.
 */
private fun attachOnly(pid: Long): Throwable? {
    val vmClass = Class.forName("com.sun.tools.attach.VirtualMachine")
    val attach = vmClass.getMethod("attach", String::class.java)
    return try {
        val vm = attach.invoke(null, pid.toString())
        runCatching { vmClass.getMethod("detach").invoke(vm) }
        null
    } catch (ex: ReflectiveOperationException) {
        ex.cause ?: ex
    }
}

private const val UNUSED_PID_FLOOR: Long = 2_000_000_000L

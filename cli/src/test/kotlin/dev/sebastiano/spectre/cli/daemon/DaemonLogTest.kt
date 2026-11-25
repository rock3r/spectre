package dev.sebastiano.spectre.cli.daemon

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DaemonLogTest {
    @Test
    fun `tail reads only bounded suffix of sparse file`() {
        val path = Files.createTempFile("spectre-sparse-log", ".log")
        val suffix = "bounded-tail".toByteArray()
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            channel.position(2L * 1024 * 1024 * 1024)
            channel.write(ByteBuffer.wrap(suffix))
        }

        assertContentEquals(suffix, DaemonLog.readLogTail(path, suffix.size))
    }

    @Test
    fun `rotation truncates live inode and later appends remain visible`() {
        val path = Files.createTempFile("spectre-live-log", ".log")
        FileChannel.open(path, StandardOpenOption.APPEND).use { writer ->
            writer.write(ByteBuffer.wrap("0123456789".toByteArray()))
            DaemonLog.rotateIfNeeded(path, maxBytes = 8, retainedBytes = 4)
            writer.write(ByteBuffer.wrap("after".toByteArray()))
        }

        assertEquals("6789after", Files.readString(path))
    }

    @Test
    fun `forked writer retains final data after launcher exits and concurrent rotations`(
        @TempDir directory: Path
    ) {
        val path = Files.createFile(directory.resolve("daemon.log"))
        val fixture = ForkedLogWriter::class.java
        val launcher =
            ProcessBuilder(
                    ProcessHandle.current().info().command().orElseThrow(),
                    "-cp",
                    Path.of(fixture.protectionDomain.codeSource.location.toURI()).toString(),
                    fixture.name,
                    "launch",
                    path.toString(),
                )
                .start()
        try {
            assertTrue(launcher.waitFor(15, TimeUnit.SECONDS), "Launcher did not exit")
            assertEquals(0, launcher.exitValue())
            DaemonLog(path, maxBytes = 256, retainedBytes = 128, checkIntervalMillis = 1).use {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!Files.exists(directory.resolve("done")) && System.nanoTime() < deadline) {
                    Thread.sleep(5)
                }
                assertTrue(Files.exists(directory.resolve("done")), "Writer did not finish")
            }
            assertEquals("x".repeat(128) + "FINAL-RETAINED\n", Files.readString(path))
        } finally {
            launcher.destroyForcibly()
        }
    }
}

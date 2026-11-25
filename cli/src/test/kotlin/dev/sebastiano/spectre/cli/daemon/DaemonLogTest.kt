package dev.sebastiano.spectre.cli.daemon

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `forked writer retains final data after launcher exits and concurrent rotations`() {
        val path = Files.createTempFile("spectre-forked-log", ".log")
        val script =
            "(i=0; while [ \$i -lt 200 ]; do printf 'line-%03d\\n' \"\$i\" >> \"\$1\"; " +
                "i=\$((i+1)); done; printf 'FINAL-RETAINED\\n' >> \"\$1\") &"
        ProcessBuilder("/bin/sh", "-c", script, "spectre-log-writer", path.toString())
            .start()
            .waitFor()

        DaemonLog(path, maxBytes = 256, retainedBytes = 128, checkIntervalMillis = 1).use {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while ("FINAL-RETAINED" !in Files.readString(path) && System.nanoTime() < deadline) {
                Thread.sleep(5)
            }
        }

        assertTrue(Files.readString(path).endsWith("FINAL-RETAINED\n"))
    }
}

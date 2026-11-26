package dev.sebastiano.spectre.cli.daemon

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DaemonLogContinuousWriteTest {
    @Test
    fun `stderr stays bounded while the same child handle keeps appending`(
        @TempDir directory: Path
    ) {
        val log = Files.createFile(directory.resolve("daemon.log"))
        val report = directory.resolve("writer-metrics")
        val classPath =
            listOf(ContinuousLogWriter::class.java, Unit::class.java).joinToString(
                File.pathSeparator
            ) {
                Path.of(it.protectionDomain.codeSource.location.toURI()).toString()
            }
        DaemonLog(log, maxBytes = 8192, retainedBytes = 2048, checkIntervalMillis = 25).use {
            val writer =
                ProcessBuilder(
                        ProcessHandle.current().info().command().orElseThrow(),
                        "-cp",
                        classPath,
                        ContinuousLogWriter::class.java.name,
                        log.toString(),
                        report.toString(),
                    )
                    .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start()
            try {
                assertTrue(writer.waitFor(40, TimeUnit.SECONDS), "writer did not exit")
                assertEquals(0, writer.exitValue())
                val (rotations, peak) = Files.readString(report).split(" ").map(String::toLong)
                assertTrue(rotations >= 2, "only $rotations live rotations, peak $peak bytes")
                // Polling permits bursts between checks, including scheduling delays.
                assertTrue(peak < 512 * 1024, "peak $peak bytes during sustained writes")
            } finally {
                writer.destroyForcibly().waitFor()
            }
        }
        assertEquals("z".repeat(2048) + "FINAL\n", Files.readString(log))
    }
}

internal object ContinuousLogWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val log = Path.of(args[0])
        var previous = 0L
        var peak = 0L
        var rotations = 0
        val block = ByteArray(4096) { 'a'.code.toByte() }
        repeat(400) { index ->
            if (index == 200) block.fill('z'.code.toByte())
            System.err.write(block)
            System.err.flush()
            val size = Files.size(log)
            if (size < previous) rotations++
            peak = maxOf(peak, size)
            previous = size
            Thread.sleep(2)
        }
        Files.writeString(Path.of(args[1]), "$rotations $peak")
        // Keep the inherited handle open through the last rotation, then append distinct bytes.
        System.err.write(ByteArray(8193) { 'z'.code.toByte() })
        System.err.flush()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (Files.size(log) != 2048L && System.nanoTime() < deadline) Thread.sleep(5)
        System.err.print("FINAL\n")
        System.err.flush()
    }
}

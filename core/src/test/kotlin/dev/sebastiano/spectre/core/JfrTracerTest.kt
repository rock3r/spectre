package dev.sebastiano.spectre.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JfrTracerTest {

    @Test
    fun `start then stop writes a non-empty jfr file at the requested path`() {
        val tracer = JfrTracer()
        val output = Files.createTempFile("spectre-tracer-test-", ".jfr")
        try {
            tracer.start()
            // A short workload so JFR has at least one event sample to capture. We do not
            // assert specific events because the JFR profile and JDK version control that.
            repeat(1000) {
                @Suppress("UnusedPrivateMember") val ignored = it.toString()
            }
            tracer.stop(output)

            assertTrue(Files.exists(output), "Trace file should exist at $output")
            assertTrue(output.fileSize() > 0, "Trace file should not be empty")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop without start throws`() {
        val tracer = JfrTracer()
        val output = Files.createTempFile("spectre-tracer-test-", ".jfr")
        try {
            assertFailsWith<IllegalStateException> { tracer.stop(output) }
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop with an unwritable output still resets the tracer state`() {
        val tracer = JfrTracer()
        // A path under a non-existent parent we cannot create — Files.createDirectories
        // throws and stop() must still close the recording.
        val unwritable = Path.of("/proc/spectre-cannot-write-here.jfr")

        tracer.start()
        runCatching { tracer.stop(unwritable) }
            .also { assertTrue(it.isFailure, "stop should propagate the IO failure") }

        // The tracer must be in a clean state — start() should succeed without throwing
        // "already recording", and a follow-up stop() must succeed too.
        val output = Files.createTempFile("spectre-tracer-test-", ".jfr")
        try {
            tracer.start()
            tracer.stop(output)
            assertTrue(output.fileSize() > 0, "Recovery recording should produce a file")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start while already recording throws`() {
        val tracer = JfrTracer()
        val output = Files.createTempFile("spectre-tracer-test-", ".jfr")
        try {
            tracer.start()
            try {
                assertFailsWith<IllegalStateException> { tracer.start() }
            } finally {
                tracer.stop(output)
            }
        } finally {
            output.deleteIfExists()
        }
    }
}

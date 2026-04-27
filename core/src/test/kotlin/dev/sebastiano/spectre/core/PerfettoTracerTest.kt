package dev.sebastiano.spectre.core

import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PerfettoTracerTest {

    @Test
    fun `start then stop writes a non-empty perfetto trace into the output directory`() {
        val tracer = PerfettoTracer()
        val output = Files.createTempDirectory("spectre-perfetto-test-")
        try {
            tracer.start(output)
            tracer.stop()

            val files = output.listDirectoryEntries()
            assertTrue(files.isNotEmpty(), "Output directory should contain at least one file")
            assertTrue(
                files.any { it.fileSize() > 0 },
                "At least one trace file should be non-empty",
            )
        } finally {
            output.listDirectoryEntries().forEach { it.deleteIfExists() }
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop without start throws`() {
        val tracer = PerfettoTracer()
        assertFailsWith<IllegalStateException> { tracer.stop() }
    }

    @Test
    fun `start while already recording throws`() {
        val tracer = PerfettoTracer()
        val output = Files.createTempDirectory("spectre-perfetto-test-")
        try {
            tracer.start(output)
            try {
                assertFailsWith<IllegalStateException> { tracer.start(output) }
            } finally {
                tracer.stop()
            }
        } finally {
            output.listDirectoryEntries().forEach { it.deleteIfExists() }
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop resets the tracer state so it can be reused`() {
        val tracer = PerfettoTracer()
        val first = Files.createTempDirectory("spectre-perfetto-test-")
        val second = Files.createTempDirectory("spectre-perfetto-test-")
        try {
            tracer.start(first)
            tracer.stop()
            // Should not throw "already recording" — state must have reset.
            tracer.start(second)
            tracer.stop()
        } finally {
            first.listDirectoryEntries().forEach { it.deleteIfExists() }
            first.deleteIfExists()
            second.listDirectoryEntries().forEach { it.deleteIfExists() }
            second.deleteIfExists()
        }
    }
}

package dev.sebastiano.spectre.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class WithTracingTest {

    @Test
    fun `withTracing brackets the block with start and stop`() = runTest {
        val tracer = RecordingTracer()
        val output = Path.of("test-output")

        val result =
            withTracingInternal(output = output, tracer = tracer) {
                tracer.recordEvent("inside-block")
                42
            }

        assertEquals(42, result)
        assertEquals(listOf("start:test-output", "inside-block", "stop"), tracer.events)
    }

    @Test
    fun `withTracing stops the tracer even when the block throws`() = runTest {
        val tracer = RecordingTracer()
        val output = Path.of("crash")

        val raised =
            assertFailsWith<RuntimeException> {
                withTracingInternal(output = output, tracer = tracer) {
                    tracer.recordEvent("about-to-throw")
                    error("boom")
                }
            }

        assertEquals("boom", raised.message)
        assertEquals(listOf("start:crash", "about-to-throw", "stop"), tracer.events)
    }

    @Test
    fun `withTracing returns the block result`() = runTest {
        val sentinel = Any()

        val result = withTracingInternal(output = Path.of("x"), tracer = NoOpTracer()) { sentinel }

        assertSame(sentinel, result)
    }

    @Test
    fun `withTracing rethrows tracer stop failures while preserving block exceptions`() = runTest {
        val tracer =
            object : Tracer {
                var started = false

                override fun start(output: Path) {
                    started = true
                }

                override fun stop() {
                    error("stop failed")
                }
            }
        val raised =
            assertFailsWith<IllegalStateException> {
                withTracingInternal(output = Path.of("x"), tracer = tracer) {
                    error("block failed")
                }
            }
        // The original block exception is preferred over the suppressed stop failure.
        assertEquals("block failed", raised.message)
        assertTrue(tracer.started, "Tracer should still have been started")
        val suppressed = raised.suppressed
        assertEquals(1, suppressed.size, "stop failure should be attached as suppressed")
        assertEquals("stop failed", suppressed[0].message)
    }
}

private class RecordingTracer : Tracer {
    val events = mutableListOf<String>()

    fun recordEvent(name: String) {
        events += name
    }

    override fun start(output: Path) {
        events += "start:${output.fileName}"
    }

    override fun stop() {
        events += "stop"
    }
}

private class NoOpTracer : Tracer {
    override fun start(output: Path) = Unit

    override fun stop() = Unit
}

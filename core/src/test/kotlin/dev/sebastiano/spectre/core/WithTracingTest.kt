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
        val automator = ComposeAutomator.inProcess()
        val output = Path.of("test-output.jfr")

        val result =
            automator.withTracing(output = output, tracer = tracer) {
                tracer.recordEvent("inside-block")
                42
            }

        assertEquals(42, result)
        assertEquals(listOf("start", "inside-block", "stop:test-output.jfr"), tracer.events)
    }

    @Test
    fun `withTracing stops the tracer even when the block throws`() = runTest {
        val tracer = RecordingTracer()
        val automator = ComposeAutomator.inProcess()
        val output = Path.of("crash.jfr")

        val raised =
            assertFailsWith<RuntimeException> {
                automator.withTracing(output = output, tracer = tracer) {
                    tracer.recordEvent("about-to-throw")
                    error("boom")
                }
            }

        assertEquals("boom", raised.message)
        assertEquals(listOf("start", "about-to-throw", "stop:crash.jfr"), tracer.events)
    }

    @Test
    fun `withTracing returns the block result`() = runTest {
        val automator = ComposeAutomator.inProcess()
        val sentinel = Any()

        val result =
            automator.withTracing(output = Path.of("x.jfr"), tracer = NoOpTracer()) { sentinel }

        assertSame(sentinel, result)
    }

    @Test
    fun `withTracing rethrows tracer stop failures while preserving block exceptions`() = runTest {
        val tracer =
            object : Tracer {
                var started = false

                override fun start() {
                    started = true
                }

                override fun stop(output: Path) {
                    error("stop failed")
                }
            }
        val automator = ComposeAutomator.inProcess()
        val raised =
            assertFailsWith<IllegalStateException> {
                automator.withTracing(output = Path.of("x.jfr"), tracer = tracer) {
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

    override fun start() {
        events += "start"
    }

    override fun stop(output: Path) {
        events += "stop:${output.fileName}"
    }
}

private class NoOpTracer : Tracer {
    override fun start() = Unit

    override fun stop(output: Path) = Unit
}

package dev.sebastiano.spectre.testing

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

/**
 * Contract tests for [runSpectreTest]: real wall-clock delays, leak detection, and exception
 * propagation. These drive the shipped runner entry point — not a reimplemented stub.
 */
class RunSpectreTestTest {

    @Test
    fun `delay inside runSpectreTest consumes real wall-clock time`() {
        val hold = 200.milliseconds
        val started = System.nanoTime()
        runSpectreTest { delay(hold) }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertTrue(
            elapsedMs >= hold.inWholeMilliseconds,
            "expected delay($hold) to take ≥${hold.inWholeMilliseconds}ms wall time, was ${elapsedMs}ms",
        )
    }

    @Test
    fun `multi-step delay budget matches swipe-style pacing wall time`() {
        // Mirrors swipe step pauses: several short delays that must not collapse to ~0.
        val steps = 4
        val pausePerStep = 50.milliseconds
        val minExpectedMs = (pausePerStep.inWholeMilliseconds * steps)
        val started = System.nanoTime()
        runSpectreTest { repeat(steps) { delay(pausePerStep) } }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertTrue(
            elapsedMs >= minExpectedMs,
            "expected $steps×$pausePerStep ≥${minExpectedMs}ms wall time, was ${elapsedMs}ms",
        )
    }

    @Test
    fun `clipboard-style poll delays consume real wall time`() {
        // Mirrors awaitClipboardContents: poll interval delays between reads.
        val polls = 5
        val pollMs = 40.milliseconds
        val minExpectedMs = pollMs.inWholeMilliseconds * polls
        val started = System.nanoTime()
        runSpectreTest { repeat(polls) { delay(pollMs) } }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        assertTrue(
            elapsedMs >= minExpectedMs,
            "expected $polls×$pollMs ≥${minExpectedMs}ms wall time, was ${elapsedMs}ms",
        )
    }

    @Test
    fun `unfinished child coroutine fails the test with a leak report`() {
        val error =
            assertFailsWith<AssertionError> {
                runSpectreTest {
                    launch { delay(30.seconds) }
                    // Body returns while the child is still active — leak.
                }
            }
        val message = error.message.orEmpty()
        assertTrue(
            message.contains("unfinished coroutine", ignoreCase = true) ||
                message.contains("coroutine leak", ignoreCase = true),
            "expected clear leak wording, was: $message",
        )
    }

    @Test
    fun `joined child coroutine is not reported as a leak`() {
        runSpectreTest {
            val job = launch { delay(20.milliseconds) }
            job.join()
        }
    }

    @Test
    fun `exceptions from the test body propagate to the caller`() {
        val error =
            assertFailsWith<IllegalStateException> {
                runSpectreTest { error("boom from spectre test body") }
            }
        assertTrue(error.message?.contains("boom from spectre test body") == true)
    }

    @Test
    fun `exceptions from child coroutines propagate to the caller`() {
        val error =
            assertFailsWith<IllegalStateException> {
                runSpectreTest {
                    val deferred = async { error("child failed") }
                    deferred.await()
                }
            }
        assertTrue(error.message?.contains("child failed") == true)
    }

    @Test
    fun `unawaited child failure is not swallowed after the body returns`() {
        val error =
            assertFailsWith<IllegalStateException> {
                runSpectreTest {
                    // UNDISPATCHED runs the child to its first suspension (or completion) before
                    // launch returns, so the failure is reported before the body returns without
                    // the body awaiting the child.
                    launch(start = CoroutineStart.UNDISPATCHED) { error("unawaited child boom") }
                }
            }
        assertTrue(
            error.message?.contains("unawaited child boom") == true,
            "expected unawaited child failure to surface, was: ${error.message}",
        )
    }

    @Test
    fun `joined launch failure is not swallowed`() {
        // join() does not rethrow child failures; the runner must still surface them.
        val error =
            assertFailsWith<IllegalStateException> {
                runSpectreTest {
                    val job = launch { error("joined child boom") }
                    job.join()
                }
            }
        assertTrue(
            error.message?.contains("joined child boom") == true,
            "expected joined launch failure to surface, was: ${error.message}",
        )
    }

    @Test
    fun `unawaited async failure is not swallowed after the body returns`() {
        val error =
            assertFailsWith<IllegalStateException> {
                runSpectreTest {
                    async(start = CoroutineStart.UNDISPATCHED) { error("unawaited async boom") }
                }
            }
        assertTrue(
            error.message?.contains("unawaited async boom") == true,
            "expected unawaited async failure to surface, was: ${error.message}",
        )
    }

    @Test
    fun `returns the body result for expression-body JUnit shapes`() {
        val value: Int = runSpectreTest { 42 }
        assertEquals(42, value)
    }

    @Test
    fun `timeout cancels a runaway body`() {
        val error =
            assertFailsWith<AssertionError> {
                runSpectreTest(timeout = 80.milliseconds) { delay(30.seconds) }
            }
        val message = error.message.orEmpty()
        assertTrue(
            message.contains("timed out", ignoreCase = true) ||
                message.contains("timeout", ignoreCase = true),
            "expected timeout wording, was: $message",
        )
    }

    @Test
    fun `nested withTimeout failures are not rewritten as runner timeout`() {
        val error =
            assertFailsWith<TimeoutCancellationException> {
                runSpectreTest(timeout = 30.seconds) {
                    // Inner budget much smaller than the runner budget — must surface as the
                    // nested timeout, not "runSpectreTest timed out after 30s".
                    withTimeout(50.milliseconds) { delay(5.seconds) }
                }
            }
        assertTrue(
            error.message?.contains("runSpectreTest timed out") != true,
            "nested timeout must not be rewritten as runner timeout, was: ${error.message}",
        )
    }

    @Test
    fun `nullable body result is not treated as a timeout`() {
        val value: String? = runSpectreTest { null }
        assertEquals(null, value)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

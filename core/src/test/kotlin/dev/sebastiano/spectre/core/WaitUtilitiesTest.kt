package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest

class WaitUtilitiesTest {

    @Test
    fun `returns immediately when predicate succeeds on first call`() = runTest {
        val result = waitUntil(timeout = 5.seconds, pollInterval = 100.milliseconds) { "found" }
        assertEquals("found", result)
    }

    @Test
    fun `retries until predicate succeeds`() = runTest {
        var calls = 0
        val result =
            waitUntil(timeout = 5.seconds, pollInterval = 10.milliseconds) {
                calls++
                if (calls >= 3) "found" else null
            }
        assertEquals("found", result)
        assertEquals(3, calls)
    }

    @Test
    fun `throws TimeoutCancellationException when timeout expires`() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            waitUntil(timeout = 100.milliseconds, pollInterval = 10.milliseconds) { null }
        }
    }

    @Test
    fun `predicate is called multiple times before timeout`() = runTest {
        var calls = 0
        try {
            waitUntil(timeout = 200.milliseconds, pollInterval = 10.milliseconds) {
                calls++
                null
            }
        } catch (_: TimeoutCancellationException) {
            // expected
        }
        // With 200ms timeout and 10ms poll, we should get at least a few calls
        assert(calls > 1) { "Expected multiple calls, got $calls" }
    }
}

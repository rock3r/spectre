package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

class WaitForIdleTest {

    @Test
    fun `waitForIdle returns when fingerprint stays stable for the quiet period`() = runTest {
        val clock = FakeClock()
        var drained = 0

        waitForIdleInternal(
            timeout = 1.seconds,
            quietPeriod = 64.milliseconds,
            pollInterval = 16.milliseconds,
            idlingResources = { emptyList() },
            drainEdt = { drained++ },
            fingerprint = { "stable" },
            clock = clock,
            sleep = clock::advance,
        )

        assertTrue(drained > 0, "EDT should be drained at least once")
    }

    @Test
    fun `waitForIdle restarts the quiet window when the fingerprint changes`() = runTest {
        val clock = FakeClock()
        val sequence = ArrayDeque(listOf("a", "a", "b", "b", "b", "b", "b", "b"))
        val readings = mutableListOf<String>()

        waitForIdleInternal(
            timeout = 1.seconds,
            quietPeriod = 48.milliseconds,
            pollInterval = 16.milliseconds,
            idlingResources = { emptyList() },
            drainEdt = {},
            fingerprint = {
                val next = sequence.removeFirst()
                readings += next
                next
            },
            clock = clock,
            sleep = clock::advance,
        )

        assertTrue(
            readings.dropWhile { it == "a" }.count { it == "b" } >= 4,
            "Quiet window should restart on the a→b flip; readings were $readings",
        )
    }

    @Test
    fun `waitForIdle waits while idling resources report busy`() = runTest {
        val clock = FakeClock()
        var sampledWhileBusy = 0
        var idleAfterTicks = 5
        val resource =
            object : AutomatorIdlingResource {
                override val isIdleNow: Boolean
                    get() {
                        if (idleAfterTicks > 0) {
                            sampledWhileBusy++
                            idleAfterTicks--
                            return false
                        }
                        return true
                    }
            }

        waitForIdleInternal(
            timeout = 1.seconds,
            quietPeriod = 16.milliseconds,
            pollInterval = 16.milliseconds,
            idlingResources = { listOf(resource) },
            drainEdt = {},
            fingerprint = { "stable" },
            clock = clock,
            sleep = clock::advance,
        )

        assertEquals(5, sampledWhileBusy, "Should poll the resource until it reports idle")
    }

    @Test
    fun `waitForIdle throws when resources never go idle`() = runTest {
        val clock = FakeClock()
        val busyResource =
            object : AutomatorIdlingResource {
                override val isIdleNow: Boolean = false

                override fun diagnosticMessage(): String = "network in flight"
            }

        val error =
            assertFailsWith<IdleTimeoutException> {
                waitForIdleInternal(
                    timeout = 80.milliseconds,
                    quietPeriod = 16.milliseconds,
                    pollInterval = 16.milliseconds,
                    idlingResources = { listOf(busyResource) },
                    drainEdt = {},
                    fingerprint = { "stable" },
                    clock = clock,
                    sleep = clock::advance,
                )
            }

        assertTrue(
            error.message?.contains("network in flight") == true,
            "Diagnostic should be surfaced: ${error.message}",
        )
    }

    @Test
    fun `waitForIdle throws when fingerprint never settles`() = runTest {
        val clock = FakeClock()
        var counter = 0

        assertFailsWith<IdleTimeoutException> {
            waitForIdleInternal(
                timeout = 80.milliseconds,
                quietPeriod = 32.milliseconds,
                pollInterval = 16.milliseconds,
                idlingResources = { emptyList() },
                drainEdt = {},
                fingerprint = { (counter++).toString() },
                clock = clock,
                sleep = clock::advance,
            )
        }
    }
}

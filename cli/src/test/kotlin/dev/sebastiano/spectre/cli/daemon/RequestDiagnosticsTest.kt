package dev.sebastiano.spectre.cli.daemon

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestDiagnosticsTest {
    @AfterTest
    fun clearDiagnostics() {
        RequestDiagnostics.clear()
    }

    @Test
    fun `delayed helper warning is delivered once to its originating session`() {
        lateinit var delayed: () -> Unit
        val (_, immediate) =
            RequestDiagnostics.capture("one") {
                delayed = RequestDiagnostics.route { RequestDiagnostics.report("late warning") }
            }

        delayed()
        val (_, otherSession) = RequestDiagnostics.capture("two") {}
        val (_, originatingSession) = RequestDiagnostics.capture("one") {}
        val (_, noReplay) = RequestDiagnostics.capture("one") {}

        assertTrue(immediate.isEmpty())
        assertTrue(otherSession.isEmpty())
        assertEquals(listOf("late warning"), originatingSession)
        assertTrue(noReplay.isEmpty())
    }

    @Test
    fun `routed failure on a reused pool thread retains stable session attribution`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            lateinit var first: () -> Unit
            lateinit var second: () -> Unit
            RequestDiagnostics.capture("first") {
                first = RequestDiagnostics.route {
                    RequestDiagnostics.report("first failed: operation failure")
                }
            }
            RequestDiagnostics.capture("second") {
                second = RequestDiagnostics.route { RequestDiagnostics.report("second warning") }
            }
            executor.submit(first).get(5, TimeUnit.SECONDS)
            executor.submit(second).get(5, TimeUnit.SECONDS)

            assertEquals(
                listOf("first failed: operation failure"),
                RequestDiagnostics.capture("first") {}.second,
            )
            assertEquals(
                listOf("second warning"),
                RequestDiagnostics.capture("second") {}.second,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `count and length limits are signalled while retaining bounded warnings`() {
        val (_, warnings) =
            RequestDiagnostics.capture("bounded") {
                repeat(101) { RequestDiagnostics.report("warning-$it") }
                RequestDiagnostics.report("x".repeat(5_000))
            }

        assertEquals(100, warnings.size)
        assertTrue(warnings.last().contains("2 additional warning(s)"))
        assertTrue(warnings.last().contains("warning text beyond 4096 characters"))
        assertTrue(warnings.last().contains("daemon log"))
    }

    @Test
    fun `session cleanup discards delayed warnings`() {
        lateinit var delayed: () -> Unit
        RequestDiagnostics.capture("gone") {
            delayed = RequestDiagnostics.route { RequestDiagnostics.report("obsolete") }
        }
        delayed()
        RequestDiagnostics.forget("gone")

        assertTrue(RequestDiagnostics.capture("gone") {}.second.isEmpty())
    }
}

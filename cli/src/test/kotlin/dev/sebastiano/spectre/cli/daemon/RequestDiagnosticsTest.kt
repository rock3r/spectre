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
        assertEquals((0..98).map { "warning-$it" }, warnings.dropLast(1))
        assertEquals(
            "[spectre-daemon] diagnostics truncated (discarded 3 warning(s) and " +
                "904 warning character(s)); recent diagnostics may remain in the retained " +
                "suffix of the per-socket daemon log",
            warnings.last(),
        )
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

    @Test
    fun `late helper after cleanup cannot leak into a reused session id`() {
        lateinit var delayed: () -> Unit
        RequestDiagnostics.capture("reused") {
            delayed = RequestDiagnostics.route { RequestDiagnostics.report("retired warning") }
        }
        RequestDiagnostics.forget("reused")
        delayed()

        val (_, reused) =
            RequestDiagnostics.capture("reused") { RequestDiagnostics.report("new warning") }

        assertEquals(listOf("new warning"), reused)
    }

    @Test
    fun `warning crossing response boundary is delivered exactly once`() {
        lateinit var delayed: () -> Unit
        val (_, first) =
            RequestDiagnostics.capture("boundary") {
                delayed = RequestDiagnostics.route { RequestDiagnostics.report("crossing") }
            }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit(delayed).get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
        val (_, second) = RequestDiagnostics.capture("boundary") {}
        val (_, third) = RequestDiagnostics.capture("boundary") {}

        assertTrue(first.isEmpty())
        assertEquals(listOf("crossing"), second)
        assertTrue(third.isEmpty())
    }
}

package dev.sebastiano.spectre.cli.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DaemonSessionRegistryTest {
    @Test
    fun `attach creates stable session ids keyed by pid`() {
        val registry = DaemonSessionRegistry()

        val first = assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234)))
        val second = assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(1234)))

        assertEquals(first, second)
        assertEquals("pid-1234", first.sessionId)
    }

    @Test
    fun `list sessions returns attached pid summaries`() {
        val registry = DaemonSessionRegistry()

        registry.handle(DaemonRequest.Attach(1234))
        registry.handle(DaemonRequest.Attach(5678))

        val response =
            assertIs<DaemonResponse.Sessions>(registry.handle(DaemonRequest.ListSessions))
        assertEquals(
            listOf(
                DaemonSessionSummary(sessionId = "pid-1234", targetPid = 1234),
                DaemonSessionSummary(sessionId = "pid-5678", targetPid = 5678),
            ),
            response.sessions,
        )
    }

    @Test
    fun `detach removes sessions by id and reports missing sessions`() {
        val registry = DaemonSessionRegistry()
        registry.handle(DaemonRequest.Attach(1234))

        assertEquals(
            DaemonResponse.Detached("pid-1234"),
            registry.handle(DaemonRequest.Detach("pid-1234")),
        )
        assertEquals(
            DaemonResponse.Sessions(emptyList()),
            registry.handle(DaemonRequest.ListSessions),
        )

        val missing =
            assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Detach("pid-1234")))
        assertEquals(DaemonErrorCode.SessionNotFound, missing.code)
    }

    @Test
    fun `shutdown clears sessions and rejects subsequent attach`() {
        val registry = DaemonSessionRegistry()
        registry.handle(DaemonRequest.Attach(1234))

        assertEquals(DaemonResponse.ShuttingDown, registry.handle(DaemonRequest.Shutdown))
        assertTrue(registry.isShutdown)
        assertEquals(
            DaemonResponse.Sessions(emptyList()),
            registry.handle(DaemonRequest.ListSessions),
        )

        val rejected = assertIs<DaemonResponse.Error>(registry.handle(DaemonRequest.Attach(5678)))
        assertEquals(DaemonErrorCode.ShutdownInProgress, rejected.code)
    }
}

package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectreCliTest {
    @Test
    fun `daemon status prints stable JSON session output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.ListSessions, request)
                    DaemonResponse.Sessions(
                        listOf(DaemonSessionSummary(sessionId = "pid-42", targetPid = 42))
                    )
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("daemon", "status", "--json")))
        assertEquals(
            "{\"version\":1,\"sessions\":[{\"id\":\"pid-42\",\"pid\":42}]}\n",
            output.toString(),
        )
    }

    @Test
    fun `daemon status prints human-readable session output`() {
        val output = StringBuilder()
        val cli = SpectreCli(request = { DaemonResponse.Sessions(emptyList()) }, output = output)

        assertEquals(0, cli.run(listOf("daemon", "status")))
        assertEquals("No daemon sessions.\n", output.toString())
    }

    @Test
    fun `usage errors are printed and returned as a nonzero exit code`() {
        val output = StringBuilder()
        val cli = SpectreCli(request = { error("Daemon should not be contacted") }, output = output)

        assertEquals(1, cli.run(listOf("unknown")))
        assertTrue(output.contains("unknown"))
    }
}

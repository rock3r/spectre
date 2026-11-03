package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import kotlin.test.Test
import kotlin.test.assertEquals

class FocusWindowCliTest {
    @Test
    fun `focus-window focuses the first window by default`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.FocusWindow("pid-42", windowIndex = 0), request)
                    DaemonResponse.Completed("pid-42")
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("focus-window", "pid-42")))
        assertEquals("Focused window 0.\n", output.toString())
    }

    @Test
    fun `focus-window forwards the window index and prints stable JSON completion output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.FocusWindow("pid-42", windowIndex = 2), request)
                    DaemonResponse.Completed("pid-42")
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("focus-window", "pid-42", "--window", "2", "--json")))
        assertEquals("{\"version\":1,\"id\":\"pid-42\"}\n", output.toString())
    }
}

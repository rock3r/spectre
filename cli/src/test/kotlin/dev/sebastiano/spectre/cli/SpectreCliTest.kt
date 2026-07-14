package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonJvmProcessSummary
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionSummary
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpectreCliTest {
    @Test
    fun `ps prints stable JSON JVM process output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(
                        ProcessHandle.current().pid(),
                        assertIs<DaemonRequest.ListJvmProcesses>(request).requesterPid,
                    )
                    DaemonResponse.JvmProcesses(
                        listOf(DaemonJvmProcessSummary(pid = 42, displayName = "com.example.App"))
                    )
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("ps", "--json")))
        assertEquals(
            "{\"version\":1,\"processes\":[{\"pid\":42,\"displayName\":\"com.example.App\"}]}\n",
            output.toString(),
        )
    }

    @Test
    fun `ps reports daemon discovery errors without a stack trace`() {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = {
                    DaemonResponse.Error(
                        code = dev.sebastiano.spectre.cli.daemon.DaemonErrorCode.AttachFailed,
                        message = "The JDK Attach API is not available",
                    )
                },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(1, cli.run(listOf("ps")))
        assertEquals("", output.toString())
        assertEquals(
            "Spectre daemon error: The JDK Attach API is not available\n",
            errorOutput.toString(),
        )
    }

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
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("Daemon should not be contacted") },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(1, cli.run(listOf("unknown")))
        assertEquals("", output.toString())
        assertTrue(errorOutput.contains("unknown"))
    }

    @Test
    fun `daemon I O errors are reported without a stack trace`() {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { throw IOException("Socket unavailable") },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(1, cli.run(listOf("daemon", "status")))
        assertEquals("", output.toString())
        assertEquals("Spectre daemon error: Socket unavailable\n", errorOutput.toString())
    }
}

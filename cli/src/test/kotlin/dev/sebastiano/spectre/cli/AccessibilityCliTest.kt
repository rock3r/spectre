package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.AccessibilitySnapshot
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class AccessibilityCliTest {
    @Test
    fun `tree stdout is JSON and notices stay on stderr`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var seen: DaemonRequest.AccessibilityTree? = null
        val cli =
            SpectreCli(
                request = { request ->
                    seen = assertIs(request)
                    DaemonResponse.AccessibilitySnapshotResult(
                        AccessibilitySnapshot(
                            2,
                            "os-accessibility",
                            "2026-11-25T12:34:56Z",
                            emptyList(),
                            emptyList(),
                            listOf("bus enabled"),
                        )
                    )
                },
                output = stdout,
                errorOutput = stderr,
            )
        assertEquals(0, cli.run(listOf("a11y", "tree", "--pid", "42")))
        Json.parseToJsonElement(stdout.toString())
        assertEquals(42, seen?.processId)
        assertTrue(stderr.contains("notice: bus enabled"))
    }
}

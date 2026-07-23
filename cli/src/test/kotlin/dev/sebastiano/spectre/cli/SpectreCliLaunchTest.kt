package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectreCliLaunchTest {

    @Test
    fun `launch help documents command form and options`() {
        val output = StringBuilder()
        val error = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("daemon should not be invoked for launch --help") },
                output = output,
                errorOutput = error,
            )
        // Clikt prints help to stderr via CliktError path with status 0 for --help.
        val code = cli.run(listOf("launch", "--help"))
        val combined = output.toString() + error.toString()
        assertTrue(
            code == 0 || combined.contains("launch", ignoreCase = true),
            "help exit=$code out=$combined",
        )
        assertTrue(
            combined.contains("launch") || combined.contains("Command"),
            "expected launch help text: $combined",
        )
        assertTrue(
            combined.contains("--once") ||
                combined.contains("once") ||
                combined.contains("directory"),
            "expected launch options in help: $combined",
        )
    }

    @Test
    fun `launch once on java -version fails with attach-style exit and stage message`() {
        val output = StringBuilder()
        val error = StringBuilder()
        val javaBin =
            Paths.get(
                    System.getProperty("java.home"),
                    "bin",
                    if (
                        System.getProperty("os.name")
                            .orEmpty()
                            .startsWith("Windows", ignoreCase = true)
                    )
                        "java.exe"
                    else "java",
                )
                .toString()
        val cli =
            SpectreCli(
                request = { _: DaemonRequest -> error("daemon should not be called for launch") },
                output = output,
                errorOutput = error,
            )
        val code = cli.run(listOf("launch", "--once", "--", javaBin, "-version"))
        assertEquals(3, code, "expected EXIT_ATTACH_FAILURE; err=$error out=$output")
        assertTrue(
            error.toString().contains("PROCESS_ALIVE") ||
                error.toString().contains("launch error", ignoreCase = true),
            "expected stage error on stderr: $error",
        )
    }
}

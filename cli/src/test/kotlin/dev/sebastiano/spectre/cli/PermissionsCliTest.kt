package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLI `permissions` is local (no daemon). On non-macOS CI/Linux runners, preflight returns
 * not-applicable granted=true without invoking the helper.
 */
class PermissionsCliTest {

    @Test
    fun `permissions check succeeds on non-macOS without daemon`() {
        val out = StringBuilder()
        val err = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("daemon must not be called for permissions: $it") },
                output = out,
                errorOutput = err,
            )
        val code = cli.run(listOf("permissions", "check"))
        // Non-macOS: granted / not applicable → 0. macOS: depends on local TCC.
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("mac")) {
            assertEquals(0, code, "err=$err out=$out")
            assertTrue(
                out.toString().contains("not applicable") || out.toString().contains("granted")
            )
        } else {
            // On macOS the helper must run; exit 0 or 1 depending on grant.
            assertTrue(code == 0 || code == 1, "unexpected exit $code out=$out err=$err")
            assertTrue(out.isNotEmpty() || err.isNotEmpty())
        }
    }

    @Test
    fun `permissions check --json includes deep_link field name on non-macOS`() {
        val out = StringBuilder()
        val cli =
            SpectreCli(
                request = { _: DaemonRequest -> error("no daemon") },
                output = out,
                errorOutput = StringBuilder(),
            )
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (os.contains("mac")) return
        val code = cli.run(listOf("permissions", "check", "--json"))
        assertEquals(0, code)
        assertTrue(out.toString().contains("deepLink") || out.toString().contains("\"granted\""))
    }

    @Test
    fun `permissions check never remaps a clean run to usage failure exit 2`() {
        // Regression: SpectreCli used to map ProgramResult(1) (denied) → EXIT_USAGE_FAILURE(2).
        // A clean permissions check must exit 0 (granted/n/a) or 1 (denied), never 2.
        val out = StringBuilder()
        val err = StringBuilder()
        val code =
            SpectreCli(request = { error("no daemon") }, output = out, errorOutput = err)
                .run(listOf("permissions", "check"))
        assertTrue(
            code == 0 || code == 1,
            "permissions check exit must be 0 or 1, got $code out=$out err=$err",
        )
        assertTrue(
            !err.toString().contains("Usage:"),
            "ProgramResult/denied path must not print Clikt help; err=$err",
        )
    }

    @Test
    fun `usage errors still exit with usage failure code 2`() {
        val err = StringBuilder()
        val code =
            SpectreCli(
                    request = { error("no daemon") },
                    output = StringBuilder(),
                    errorOutput = err,
                )
                .run(listOf("permissions", "not-a-subcommand"))
        assertEquals(2, code, "err=$err")
    }
}

package dev.sebastiano.spectre.recording.screencapturekit

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * End-to-end contract tests against the real bundled `spectre-screencapture` Swift binary.
 *
 * The JVM-side `ScreenCaptureKitRecorderTest` exercises the recorder lifecycle with fake processes;
 * that catches regressions on our side but can't catch protocol drift between the Kotlin code and
 * the Swift helper (e.g. someone renames a CLI flag in main.swift but forgets to update
 * [HelperArguments]). These tests `Process`-out to the real binary with intentionally-bad argv and
 * assert the documented exit codes. Validates the CLI surface without needing SCK / TCC, so they
 * can run on any macOS host with the helper bundled.
 *
 * Skipped on non-macOS — the helper isn't built / bundled there.
 */
class ScreenCaptureKitHelperContractTest {

    private lateinit var helper: Path
    private lateinit var output: Path

    @BeforeTest
    fun setUp() {
        assumeTrue(
            System.getProperty("os.name").orEmpty().lowercase().contains("mac"),
            "Helper binary is only bundled on macOS hosts",
        )
        helper = HelperBinaryExtractor().extract()
        output = Files.createTempFile("spectre-helper-contract-", ".mov")
        // Delete the placeholder createTempFile leaves behind so paths-that-don't-exist tests
        // start from a clean slate.
        output.deleteIfExists()
    }

    @AfterTest
    fun tearDown() {
        if (::output.isInitialized) output.deleteIfExists()
    }

    @Test
    fun `helper exits 2 when invoked with no arguments`() {
        val exit = runHelper(emptyList())
        assertEquals(2, exit, "Missing required args must exit 2")
    }

    @Test
    fun `helper exits 2 on non-integer pid`() {
        val exit =
            runHelper(
                listOf(
                    "--pid",
                    "not-a-number",
                    "--title-contains",
                    "anything",
                    "--output",
                    output.toString(),
                )
            )
        assertEquals(2, exit, "Non-integer --pid must exit 2")
    }

    @Test
    fun `helper exits 2 on empty title-contains`() {
        val exit =
            runHelper(listOf("--pid", "1", "--title-contains", "", "--output", output.toString()))
        assertEquals(2, exit, "Empty --title-contains must exit 2")
    }

    @Test
    fun `helper exits 2 on missing output`() {
        val exit = runHelper(listOf("--pid", "1", "--title-contains", "anything"))
        assertEquals(2, exit, "Missing --output must exit 2")
    }

    @Test
    fun `helper exits 2 on unknown argument`() {
        val exit =
            runHelper(
                listOf(
                    "--pid",
                    "1",
                    "--title-contains",
                    "anything",
                    "--output",
                    output.toString(),
                    "--unknown-flag",
                    "value",
                )
            )
        assertEquals(2, exit, "Unknown flag must exit 2")
    }

    @Test
    fun `helper exits 2 on negative discovery-timeout`() {
        val exit =
            runHelper(
                listOf(
                    "--pid",
                    "1",
                    "--title-contains",
                    "x",
                    "--output",
                    output.toString(),
                    "--discovery-timeout-ms",
                    "-1",
                )
            )
        assertEquals(2, exit, "Negative --discovery-timeout-ms must exit 2")
    }

    @Test
    fun `helper exits 2 on non-positive fps`() {
        val exit =
            runHelper(
                listOf(
                    "--pid",
                    "1",
                    "--title-contains",
                    "x",
                    "--output",
                    output.toString(),
                    "--fps",
                    "0",
                )
            )
        assertEquals(2, exit, "fps == 0 must exit 2")
    }

    @Test
    fun `helper exits 2 on non-boolean cursor`() {
        val exit =
            runHelper(
                listOf(
                    "--pid",
                    "1",
                    "--title-contains",
                    "x",
                    "--output",
                    output.toString(),
                    "--cursor",
                    "1",
                )
            )
        assertEquals(2, exit, "--cursor accepts only 'true' or 'false', not '1'")
    }

    @Test
    fun `helper exits 2 when output points at an existing directory`() {
        val outputDir = Files.createTempDirectory("spectre-helper-contract-dir-")
        try {
            val exit =
                runHelper(
                    listOf(
                        "--pid",
                        "1",
                        "--title-contains",
                        "x",
                        "--output",
                        outputDir.toString(),
                        "--discovery-timeout-ms",
                        "100",
                    )
                )
            assertEquals(2, exit, "--output pointing at a directory must exit 2 with refusal")
            // Critical: the directory must NOT have been deleted (early helper would `rm -rf`
            // it).
            assertTrue(Files.isDirectory(outputDir), "Helper must not delete the output directory")
        } finally {
            outputDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `helper exits 3 when no window matches the discriminator`() {
        // pid 1 on macOS is launchd, which has no windows by definition. Even if it did, the
        // discriminator "spectre-contract-test-no-such-window-xyz" guarantees no match.
        val exit =
            runHelper(
                listOf(
                    "--pid",
                    "1",
                    "--title-contains",
                    "spectre-contract-test-no-such-window-xyz",
                    "--output",
                    output.toString(),
                    "--discovery-timeout-ms",
                    "200",
                )
            )
        assertEquals(3, exit, "Unmatched window discriminator must exit 3 within the timeout")
    }

    /**
     * Spawns the helper with the given argv (the helper path is prepended automatically) and waits
     * for it to exit, returning the exit code. Bounded wait so a hanging helper doesn't stall the
     * test indefinitely.
     */
    private fun runHelper(args: List<String>): Int {
        val argv = listOf(helper.toString()) + args
        val process =
            ProcessBuilder(argv)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            check(process.waitFor(HELPER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Helper did not exit within ${HELPER_TIMEOUT_SECONDS}s for argv=$argv"
            }
        } catch (e: IOException) {
            process.destroyForcibly()
            throw e
        }
        return process.exitValue()
    }

    private companion object {
        const val HELPER_TIMEOUT_SECONDS: Long = 5
    }
}

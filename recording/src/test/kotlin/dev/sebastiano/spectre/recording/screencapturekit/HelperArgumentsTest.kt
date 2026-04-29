package dev.sebastiano.spectre.recording.screencapturekit

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HelperArgumentsTest {

    @Test
    fun `toArgv places helper path first and output last`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val output = Path.of("/tmp/recording.mov")
        val args =
            HelperArguments(
                pid = 4242,
                titleContains = "Spectre/abc123",
                output = output,
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 2000,
            )

        val argv = args.toArgv(helper)

        assertEquals(helper.toString(), argv.first(), "Helper path must be argv[0]")
        assertEquals(output.toString(), argv.last(), "Output path must be the last argv entry")
    }

    @Test
    fun `toArgv emits the documented flag set`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val args =
            HelperArguments(
                pid = 4242,
                titleContains = "Spectre/abc123",
                output = Path.of("/tmp/out.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 2000,
            )

        val argv = args.toArgv(helper)

        // The helper's CLI documents `--pid`, `--title-contains`, `--fps`, `--cursor`,
        // `--discovery-timeout-ms`, `--output`. Every emitted flag must be one of those.
        val expectedFlags =
            setOf(
                "--pid",
                "--title-contains",
                "--fps",
                "--cursor",
                "--discovery-timeout-ms",
                "--output",
            )
        val emittedFlags = argv.filter { it.startsWith("--") }.toSet()
        assertEquals(expectedFlags, emittedFlags, "Argv flags must match the helper CLI contract")
    }

    @Test
    fun `toArgv encodes captureCursor as the literal true or false expected by the helper`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val baseline =
            HelperArguments(
                pid = 1,
                titleContains = "x",
                output = Path.of("/tmp/o.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 0,
            )

        val on = baseline.toArgv(helper)
        val off = baseline.copy(captureCursor = false).toArgv(helper)

        // The Swift side parses with `Bool(value)` which only accepts "true" / "false". Anything
        // else (e.g. "1"/"0") would crash with an arg-validation error at run time.
        assertEquals("true", on[on.indexOf("--cursor") + 1])
        assertEquals("false", off[off.indexOf("--cursor") + 1])
    }

    @Test
    fun `toArgv encodes pid and timeouts as decimal integers`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val args =
            HelperArguments(
                pid = 99_999,
                titleContains = "x",
                output = Path.of("/tmp/o.mov"),
                fps = 60,
                captureCursor = false,
                discoveryTimeoutMs = 1500,
            )

        val argv = args.toArgv(helper)

        assertEquals("99999", argv[argv.indexOf("--pid") + 1])
        assertEquals("60", argv[argv.indexOf("--fps") + 1])
        assertEquals("1500", argv[argv.indexOf("--discovery-timeout-ms") + 1])
    }

    @Test
    fun `constructor rejects blank titleContains`() {
        // The Swift side requires --title-contains to be non-empty (otherwise window
        // discovery degenerates to "any window owned by pid"). Surface that as a JVM-side
        // precondition so the helper never has to defend against it.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                HelperArguments(
                    pid = 1,
                    titleContains = "   ",
                    output = Path.of("/tmp/o.mov"),
                    fps = 30,
                    captureCursor = true,
                    discoveryTimeoutMs = 0,
                )
            }
        assertTrue(ex.message?.contains("titleContains") == true)
    }

    @Test
    fun `constructor rejects non-positive fps`() {
        assertFailsWith<IllegalArgumentException> {
            HelperArguments(
                pid = 1,
                titleContains = "x",
                output = Path.of("/tmp/o.mov"),
                fps = 0,
                captureCursor = true,
                discoveryTimeoutMs = 0,
            )
        }
    }

    @Test
    fun `constructor rejects negative discoveryTimeout`() {
        assertFailsWith<IllegalArgumentException> {
            HelperArguments(
                pid = 1,
                titleContains = "x",
                output = Path.of("/tmp/o.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = -1,
            )
        }
    }
}

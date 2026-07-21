package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Rectangle
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
                source = HelperSource.Window,
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
                source = HelperSource.Window,
                pid = 4242,
                titleContains = "Spectre/abc123",
                output = Path.of("/tmp/out.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 2000,
            )

        val argv = args.toArgv(helper)

        // The helper's CLI documents `--mode`, `--source`, `--pid`, `--title-contains`, `--fps`,
        // `--cursor`, `--discovery-timeout-ms`, `--output`. Every emitted flag must be one
        // of those.
        val expectedFlags =
            setOf(
                "--mode",
                "--source",
                "--pid",
                "--title-contains",
                "--fps",
                "--cursor",
                "--file-type",
                "--discovery-timeout-ms",
                "--output",
            )
        val emittedFlags = argv.filter { it.startsWith("--") }.toSet()
        assertEquals(expectedFlags, emittedFlags, "Argv flags must match the helper CLI contract")
    }

    @Test
    fun `toArgv emits region source flags`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val args =
            HelperArguments(
                source = HelperSource.Region,
                region = Rectangle(10, 20, 300, 200),
                displayIndex = 2,
                output = Path.of("/tmp/out.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 2000,
            )

        val argv = args.toArgv(helper)

        assertEquals("region", argv[argv.indexOf("--source") + 1])
        assertEquals("10,20,300,200", argv[argv.indexOf("--region") + 1])
        assertEquals("2", argv[argv.indexOf("--display-index") + 1])
    }

    @Test
    fun `toArgv emits optional window crop flag`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val args =
            HelperArguments(
                source = HelperSource.Window,
                pid = 9,
                titleContains = "Spectre/crop",
                crop = Rectangle(12, 34, 200, 100),
                output = Path.of("/tmp/out.mp4"),
                fps = 30,
                captureCursor = false,
                discoveryTimeoutMs = 1000,
            )
        val argv = args.toArgv(helper)
        assertEquals("12,34,200,100", argv[argv.indexOf("--crop") + 1])
        assertTrue("--crop" in argv)
    }

    @Test
    fun `window crop rejects non-positive dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            HelperArguments(
                source = HelperSource.Window,
                pid = 1,
                titleContains = "x",
                crop = Rectangle(0, 0, 0, 10),
                output = Path.of("/tmp/o.mp4"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 0,
            )
        }
    }

    @Test
    fun `region source rejects crop`() {
        assertFailsWith<IllegalArgumentException> {
            HelperArguments(
                source = HelperSource.Region,
                region = Rectangle(0, 0, 10, 10),
                crop = Rectangle(0, 0, 5, 5),
                output = Path.of("/tmp/o.mp4"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 0,
            )
        }
    }

    @Test
    fun `toArgv encodes captureCursor as the literal true or false expected by the helper`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val baseline =
            HelperArguments(
                source = HelperSource.Window,
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
    fun `toArgv encodes mode with recording default and screenshot override`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val baseline =
            HelperArguments(
                source = HelperSource.Window,
                pid = 1,
                titleContains = "x",
                output = Path.of("/tmp/o.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 0,
            )

        val recording = baseline.toArgv(helper)
        val screenshot = baseline.copy(mode = "screenshot").toArgv(helper)

        assertEquals("recording", recording[recording.indexOf("--mode") + 1])
        assertEquals("screenshot", screenshot[screenshot.indexOf("--mode") + 1])
    }

    @Test
    fun `toArgv encodes pid and timeouts as decimal integers`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val args =
            HelperArguments(
                source = HelperSource.Window,
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
    fun `toArgv infers mp4 file type from mp4 output extension`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val args =
            HelperArguments(
                source = HelperSource.Window,
                pid = 1,
                titleContains = "x",
                output = Path.of("/tmp/o.mp4"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 0,
            )

        val argv = args.toArgv(helper)

        assertEquals("mp4", argv[argv.indexOf("--file-type") + 1])
    }

    @Test
    fun `toArgv infers mov file type from mov output extension`() {
        val helper = Path.of("/tmp/spectre-screencapture")
        val args =
            HelperArguments(
                source = HelperSource.Window,
                pid = 1,
                titleContains = "x",
                output = Path.of("/tmp/o.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = 0,
            )

        val argv = args.toArgv(helper)

        assertEquals("mov", argv[argv.indexOf("--file-type") + 1])
    }

    @Test
    fun `constructor rejects blank titleContains`() {
        // The Swift side requires --title-contains to be non-empty (otherwise window
        // discovery degenerates to "any window owned by pid"). Surface that as a JVM-side
        // precondition so the helper never has to defend against it.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                HelperArguments(
                    source = HelperSource.Window,
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
                source = HelperSource.Window,
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
                source = HelperSource.Window,
                pid = 1,
                titleContains = "x",
                output = Path.of("/tmp/o.mov"),
                fps = 30,
                captureCursor = true,
                discoveryTimeoutMs = -1,
            )
        }
    }

    @Test
    fun `constructor rejects negative region origins`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                HelperArguments(
                    source = HelperSource.Region,
                    region = Rectangle(-1, 0, 10, 10),
                    output = Path.of("/tmp/o.mov"),
                    fps = 30,
                    captureCursor = true,
                    discoveryTimeoutMs = 0,
                )
            }

        assertTrue(ex.message?.contains("origin") == true)
    }
}

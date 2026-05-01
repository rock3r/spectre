package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmpegCliTest {

    private val ffmpeg: Path = Path.of("/usr/local/bin/ffmpeg")
    private val output: Path = Path.of("/tmp/spectre/out.mp4")
    private val region = Rectangle(100, 200, 640, 480)

    @Test
    fun `argv starts with the ffmpeg path`() {
        val argv = FfmpegCli.avfoundationRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertEquals(ffmpeg.toString(), argv.first())
    }

    @Test
    fun `argv selects avfoundation device with the configured framerate and cursor flag`() {
        val argv =
            FfmpegCli.avfoundationRegionCapture(
                ffmpeg,
                region,
                output,
                RecordingOptions(frameRate = 60, captureCursor = false),
            )

        assertContainsSequence(argv, listOf("-f", "avfoundation"))
        assertContainsSequence(argv, listOf("-framerate", "60"))
        assertContainsSequence(argv, listOf("-capture_cursor", "0"))
        // Device name (not numeric index) — stable across hardware with/without built-in cameras.
        assertContainsSequence(argv, listOf("-i", "Capture screen 0"))
    }

    @Test
    fun `argv emits a crop filter matching the requested region`() {
        val argv = FfmpegCli.avfoundationRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertContainsSequence(argv, listOf("-vf", "crop=640:480:100:200"))
    }

    @Test
    fun `argv selects the requested screen index`() {
        val argv =
            FfmpegCli.avfoundationRegionCapture(
                ffmpeg,
                region,
                output,
                RecordingOptions(screenIndex = 2),
            )
        // Multi-monitor: the device name carries the requested screen index.
        assertContainsSequence(argv, listOf("-i", "Capture screen 2"))
    }

    @Test
    fun `argv applies the configured codec and output path`() {
        val argv =
            FfmpegCli.avfoundationRegionCapture(
                ffmpeg,
                region,
                output,
                RecordingOptions(codec = "h264_videotoolbox"),
            )
        assertContainsSequence(argv, listOf("-c:v", "h264_videotoolbox"))
        assertEquals(output.toString(), argv.last())
    }

    @Test
    fun `argv always passes -y to overwrite the output file`() {
        val argv = FfmpegCli.avfoundationRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertTrue("-y" in argv, "Should pass -y to overwrite: $argv")
    }

    @Test
    fun `argv quiets ffmpeg log noise via -loglevel warning`() {
        val argv = FfmpegCli.avfoundationRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertContainsSequence(argv, listOf("-loglevel", "warning"))
    }

    @Test
    fun `negative region origin is rejected`() {
        // ffmpeg's crop filter would silently clamp these — surface the alignment problem here.
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.avfoundationRegionCapture(
                ffmpeg,
                Rectangle(-1, 0, 100, 100),
                output,
                RecordingOptions(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.avfoundationRegionCapture(
                ffmpeg,
                Rectangle(0, -10, 100, 100),
                output,
                RecordingOptions(),
            )
        }
    }

    @Test
    fun `non-positive region dimensions are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.avfoundationRegionCapture(
                ffmpeg,
                Rectangle(0, 0, 0, 100),
                output,
                RecordingOptions(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.avfoundationRegionCapture(
                ffmpeg,
                Rectangle(0, 0, 100, 0),
                output,
                RecordingOptions(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // gdigrabRegionCapture (Windows): -f gdigrab -i desktop with input-side
    // -offset_x / -offset_y / -video_size. Unlike avfoundation we do *not*
    // use the `crop` video filter — gdigrab's input-side region selection is
    // documented to support negative offsets for non-primary monitors that
    // sit to the left/above the primary display in Windows' virtual desktop
    // coordinate space, so we allow them.
    // -----------------------------------------------------------------------

    @Test
    fun `gdigrab region argv starts with the ffmpeg path`() {
        val argv = FfmpegCli.gdigrabRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertEquals(ffmpeg.toString(), argv.first())
    }

    @Test
    fun `gdigrab region argv selects gdigrab device with framerate and cursor flag`() {
        val argv =
            FfmpegCli.gdigrabRegionCapture(
                ffmpeg,
                region,
                output,
                RecordingOptions(frameRate = 60, captureCursor = false),
            )
        assertContainsSequence(argv, listOf("-f", "gdigrab"))
        assertContainsSequence(argv, listOf("-framerate", "60"))
        // gdigrab uses -draw_mouse (input option) for cursor capture, not avfoundation's
        // -capture_cursor.
        assertContainsSequence(argv, listOf("-draw_mouse", "0"))
        // The full virtual desktop is selected with `-i desktop`; the region is carved out
        // via input-side offsets and -video_size, not a crop filter.
        assertContainsSequence(argv, listOf("-i", "desktop"))
    }

    @Test
    fun `gdigrab region argv emits offset and video_size matching the requested region`() {
        val argv = FfmpegCli.gdigrabRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertContainsSequence(argv, listOf("-offset_x", "100"))
        assertContainsSequence(argv, listOf("-offset_y", "200"))
        assertContainsSequence(argv, listOf("-video_size", "640x480"))
        // No `-vf crop=...` — gdigrab does input-side region selection, so the crop filter
        // would be redundant and would re-introduce the silent-clamp pitfall avfoundation has.
        assertTrue("-vf" !in argv, "Should not use a crop filter for gdigrab: $argv")
    }

    @Test
    fun `gdigrab region argv allows negative offsets for non-primary monitors`() {
        // Windows' virtual desktop spans all monitors. A monitor positioned to the left of
        // the primary display has negative X coords; ffmpeg's gdigrab input handles this and
        // the docs explicitly call out the use case. Don't reject what ffmpeg supports.
        val negative = Rectangle(-1920, 0, 1920, 1080)
        val argv = FfmpegCli.gdigrabRegionCapture(ffmpeg, negative, output, RecordingOptions())
        assertContainsSequence(argv, listOf("-offset_x", "-1920"))
        assertContainsSequence(argv, listOf("-offset_y", "0"))
        assertContainsSequence(argv, listOf("-video_size", "1920x1080"))
    }

    @Test
    fun `gdigrab region argv applies the configured codec and output path`() {
        val argv =
            FfmpegCli.gdigrabRegionCapture(
                ffmpeg,
                region,
                output,
                RecordingOptions(codec = "libx264"),
            )
        assertContainsSequence(argv, listOf("-c:v", "libx264"))
        assertEquals(output.toString(), argv.last())
    }

    @Test
    fun `gdigrab region argv always passes -y to overwrite the output file`() {
        val argv = FfmpegCli.gdigrabRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertTrue("-y" in argv, "Should pass -y to overwrite: $argv")
    }

    @Test
    fun `gdigrab region argv quiets ffmpeg log noise via -loglevel warning`() {
        val argv = FfmpegCli.gdigrabRegionCapture(ffmpeg, region, output, RecordingOptions())
        assertContainsSequence(argv, listOf("-loglevel", "warning"))
    }

    @Test
    fun `gdigrab region argv rejects non-positive dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.gdigrabRegionCapture(
                ffmpeg,
                Rectangle(0, 0, 0, 100),
                output,
                RecordingOptions(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.gdigrabRegionCapture(
                ffmpeg,
                Rectangle(0, 0, 100, 0),
                output,
                RecordingOptions(),
            )
        }
    }

    // -----------------------------------------------------------------------
    // gdigrabWindowCapture (Windows): -f gdigrab -i title=<exact title>.
    // Title matching is exact and case-sensitive; the window must be visible
    // (not minimised). For Compose Desktop top-level windows this works; for
    // Jewel-in-IDE tool windows it doesn't (no top-level title) — callers
    // must fall back to gdigrabRegionCapture in that scenario.
    // -----------------------------------------------------------------------

    @Test
    fun `gdigrab window argv starts with the ffmpeg path`() {
        val argv = FfmpegCli.gdigrabWindowCapture(ffmpeg, "MyApp", output, RecordingOptions())
        assertEquals(ffmpeg.toString(), argv.first())
    }

    @Test
    fun `gdigrab window argv selects gdigrab with title-based input`() {
        val argv =
            FfmpegCli.gdigrabWindowCapture(
                ffmpeg,
                "Spectre Sample",
                output,
                RecordingOptions(frameRate = 60, captureCursor = true),
            )
        assertContainsSequence(argv, listOf("-f", "gdigrab"))
        assertContainsSequence(argv, listOf("-framerate", "60"))
        assertContainsSequence(argv, listOf("-draw_mouse", "1"))
        // gdigrab's title input form: `title=<exact title>` — the entire string after
        // `title=` is the match key, including any spaces.
        assertContainsSequence(argv, listOf("-i", "title=Spectre Sample"))
    }

    @Test
    fun `gdigrab window argv applies the configured codec and output path`() {
        val argv =
            FfmpegCli.gdigrabWindowCapture(
                ffmpeg,
                "MyApp",
                output,
                RecordingOptions(codec = "libx264"),
            )
        assertContainsSequence(argv, listOf("-c:v", "libx264"))
        assertEquals(output.toString(), argv.last())
    }

    @Test
    fun `gdigrab window argv always passes -y to overwrite the output file`() {
        val argv = FfmpegCli.gdigrabWindowCapture(ffmpeg, "MyApp", output, RecordingOptions())
        assertTrue("-y" in argv, "Should pass -y to overwrite: $argv")
    }

    @Test
    fun `gdigrab window argv quiets ffmpeg log noise via -loglevel warning`() {
        val argv = FfmpegCli.gdigrabWindowCapture(ffmpeg, "MyApp", output, RecordingOptions())
        assertContainsSequence(argv, listOf("-loglevel", "warning"))
    }

    @Test
    fun `gdigrab window argv rejects blank window titles`() {
        // gdigrab's title= with an empty title resolves to "the desktop title" and produces
        // confusing output. Reject blank up-front so the caller's misconfiguration surfaces
        // here instead of as a silent recording of the wrong surface.
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.gdigrabWindowCapture(ffmpeg, "", output, RecordingOptions())
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.gdigrabWindowCapture(ffmpeg, "   ", output, RecordingOptions())
        }
    }

    // -----------------------------------------------------------------------
    // x11grabRegionCapture (Linux): -f x11grab with the offset baked into the
    // input URL (`<display>+x,y`) and -video_size for the captured area. Like
    // gdigrab this uses input-side region selection — no crop filter, no
    // silent-clamp pitfall.
    // -----------------------------------------------------------------------

    @Test
    fun `x11grab region argv starts with the ffmpeg path`() {
        val argv = FfmpegCli.x11grabRegionCapture(ffmpeg, region, output, RecordingOptions(), ":0")
        assertEquals(ffmpeg.toString(), argv.first())
    }

    @Test
    fun `x11grab region argv selects x11grab device with framerate and cursor flag`() {
        val argv =
            FfmpegCli.x11grabRegionCapture(
                ffmpeg,
                region,
                output,
                RecordingOptions(frameRate = 60, captureCursor = false),
                ":0",
            )
        assertContainsSequence(argv, listOf("-f", "x11grab"))
        assertContainsSequence(argv, listOf("-framerate", "60"))
        // x11grab uses -draw_mouse (input option) for cursor capture, matching gdigrab.
        assertContainsSequence(argv, listOf("-draw_mouse", "0"))
    }

    @Test
    fun `x11grab region argv bakes offset into the input URL after the display name`() {
        // The x11grab URL form is `<display>+<x>,<y>` — the offset is part of the input URL,
        // unlike gdigrab's separate `-offset_x` / `-offset_y` pair. Verifying the exact form
        // here so a refactor that drops the `+` or swaps the comma surfaces in tests.
        val argv =
            FfmpegCli.x11grabRegionCapture(ffmpeg, region, output, RecordingOptions(), ":0.0")
        assertContainsSequence(argv, listOf("-video_size", "640x480"))
        assertContainsSequence(argv, listOf("-i", ":0.0+100,200"))
        // No `-vf crop=...` — x11grab handles the region on the input side.
        assertTrue("-vf" !in argv, "Should not use a crop filter for x11grab: $argv")
    }

    @Test
    fun `x11grab region argv honours the requested display name`() {
        // Multi-seat / multi-display X setups put each session on a different display number
        // (`:0`, `:1`, `:2`...). Make sure the value the caller passes ends up in the input URL.
        val argv = FfmpegCli.x11grabRegionCapture(ffmpeg, region, output, RecordingOptions(), ":1")
        assertContainsSequence(argv, listOf("-i", ":1+100,200"))
    }

    @Test
    fun `x11grab region argv allows negative offsets for non-primary monitors`() {
        // X11 with XRandR composites monitors into one virtual screen; depending on the user's
        // arrangement a monitor can sit at any offset within that combined space, including
        // negative coords if it's positioned to the left/above. ffmpeg's x11grab handles
        // negative offsets within the display's bounds.
        val negative = Rectangle(-1920, 0, 1920, 1080)
        val argv =
            FfmpegCli.x11grabRegionCapture(ffmpeg, negative, output, RecordingOptions(), ":0.0")
        assertContainsSequence(argv, listOf("-video_size", "1920x1080"))
        assertContainsSequence(argv, listOf("-i", ":0.0+-1920,0"))
    }

    @Test
    fun `x11grab region argv applies the configured codec and output path`() {
        val argv =
            FfmpegCli.x11grabRegionCapture(
                ffmpeg,
                region,
                output,
                RecordingOptions(codec = "libx264"),
                ":0",
            )
        assertContainsSequence(argv, listOf("-c:v", "libx264"))
        assertEquals(output.toString(), argv.last())
    }

    @Test
    fun `x11grab region argv always passes -y to overwrite the output file`() {
        val argv = FfmpegCli.x11grabRegionCapture(ffmpeg, region, output, RecordingOptions(), ":0")
        assertTrue("-y" in argv, "Should pass -y to overwrite: $argv")
    }

    @Test
    fun `x11grab region argv quiets ffmpeg log noise via -loglevel warning`() {
        val argv = FfmpegCli.x11grabRegionCapture(ffmpeg, region, output, RecordingOptions(), ":0")
        assertContainsSequence(argv, listOf("-loglevel", "warning"))
    }

    @Test
    fun `x11grab region argv rejects non-positive dimensions`() {
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.x11grabRegionCapture(
                ffmpeg,
                Rectangle(0, 0, 0, 100),
                output,
                RecordingOptions(),
                ":0",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.x11grabRegionCapture(
                ffmpeg,
                Rectangle(0, 0, 100, 0),
                output,
                RecordingOptions(),
                ":0",
            )
        }
    }

    @Test
    fun `x11grab region argv rejects blank display name`() {
        // A blank display selector is a configuration error, not a meaningful default — surface
        // it here rather than letting ffmpeg report "cannot open display +0,0" at spawn time.
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.x11grabRegionCapture(ffmpeg, region, output, RecordingOptions(), "")
        }
        assertFailsWith<IllegalArgumentException> {
            FfmpegCli.x11grabRegionCapture(ffmpeg, region, output, RecordingOptions(), "   ")
        }
    }
}

private fun assertContainsSequence(argv: List<String>, expected: List<String>) {
    val matched = argv.windowed(expected.size).any { it == expected }
    check(matched) { "Expected $expected to appear contiguously in $argv" }
}

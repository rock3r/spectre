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
}

private fun assertContainsSequence(argv: List<String>, expected: List<String>) {
    val matched = argv.windowed(expected.size).any { it == expected }
    check(matched) { "Expected $expected to appear contiguously in $argv" }
}

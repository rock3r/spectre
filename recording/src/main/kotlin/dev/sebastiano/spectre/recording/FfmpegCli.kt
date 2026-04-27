package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Builds the `ffmpeg` argv for a region capture against the avfoundation device on macOS.
 *
 * Pure function — no I/O, no process spawn — so the argv is unit-testable in isolation. The actual
 * subprocess lifecycle lives in [FfmpegRecorder].
 *
 * The resulting command captures the macOS main display (`-i 1` selects the screen capture device
 * per the spike plan), crops it to [region] via the `crop` video filter, and pipes the result
 * through the chosen codec into [output].
 */
internal object FfmpegCli {

    fun avfoundationRegionCapture(
        ffmpegPath: Path,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): List<String> {
        require(region.width > 0 && region.height > 0) {
            "region must have positive dimensions, was ${region.width}x${region.height}"
        }
        return buildList {
            add(ffmpegPath.toString())
            // Quiet ffmpeg's stderr noise to warnings+errors so callers' logs stay readable.
            add("-loglevel")
            add("warning")
            // Always overwrite — the caller named the output file deliberately and expects it
            // to be replaced if it already exists.
            add("-y")
            // avfoundation source: input frame rate, optional cursor capture, then the device
            // selector. We use the device *name* `Capture screen 0` rather than a numeric
            // index — index 1 is wrong on Macs without a built-in camera (Mac Mini, Mac Pro,
            // external-display setups) where the screen sits at index 0. The name form is
            // stable across hardware and is the form FFmpeg's avfoundation docs recommend.
            add("-f")
            add("avfoundation")
            add("-framerate")
            add(options.frameRate.toString())
            add("-capture_cursor")
            add(if (options.captureCursor) "1" else "0")
            add("-i")
            add("Capture screen 0")
            // Crop filter to the requested region.
            add("-vf")
            add("crop=${region.width}:${region.height}:${region.x}:${region.y}")
            add("-c:v")
            add(options.codec)
            add(output.toString())
        }
    }
}

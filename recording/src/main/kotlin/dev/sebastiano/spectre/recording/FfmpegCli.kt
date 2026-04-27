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
        // ffmpeg's `crop` filter silently clamps negative offsets to zero, which would record
        // the wrong region without any error signal. Reject negative coordinates outright so
        // misalignment surfaces here. Multi-monitor setups can produce negative AWT screen
        // coordinates for a secondary display; callers in that situation should translate
        // into the primary screen's coordinate space (or use ScreenCaptureKit window capture
        // when that lands in v2).
        require(region.x >= 0 && region.y >= 0) {
            "region origin must be non-negative for ffmpeg crop, was (${region.x}, ${region.y})"
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
            // selector. We use the device *name* `Capture screen N` rather than a numeric
            // index in the global device list — that global index would be wrong on Macs
            // without a built-in camera (Mac Mini, Mac Pro, external-display setups) where
            // index 0 in the global list is the screen, not the camera. The name form is
            // stable across hardware and is the form FFmpeg's avfoundation docs recommend.
            // The screen index inside the name comes from RecordingOptions.screenIndex —
            // multi-monitor users override it to point at a non-primary display.
            add("-f")
            add("avfoundation")
            add("-framerate")
            add(options.frameRate.toString())
            add("-capture_cursor")
            add(if (options.captureCursor) "1" else "0")
            add("-i")
            add("Capture screen ${options.screenIndex}")
            // Crop filter to the requested region.
            add("-vf")
            add("crop=${region.width}:${region.height}:${region.x}:${region.y}")
            add("-c:v")
            add(options.codec)
            add(output.toString())
        }
    }
}

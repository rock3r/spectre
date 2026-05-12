package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Builds `ffmpeg` argv lists for the recording backends Spectre supports.
 *
 * Pure functions — no I/O, no process spawn — so the argv is unit-testable in isolation. The actual
 * subprocess lifecycle lives in [FfmpegRecorder].
 *
 * Backends:
 * - [avfoundationRegionCapture] — macOS region capture via the avfoundation device, with cropping
 *   applied as a video filter.
 * - [gdigrabRegionCapture] — Windows region capture via the gdigrab device, with the region
 *   selected on the input side via offsets and `-video_size` (no crop filter).
 * - [gdigrabWindowCapture] — Windows title-based window capture via the gdigrab device.
 * - [x11grabRegionCapture] — Linux X11 region capture via the x11grab device, with the region
 *   selected on the input side via the `<display>+x,y` URL form and `-video_size`.
 *
 * Wayland capture is **not** here — it goes through GStreamer + xdg-desktop-portal in
 * `dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder`, separately from this ffmpeg argv
 * layer. ffmpeg ≥ 6.1 has a `pipewiregrab` indev that would slot in here, but the Spectre supported
 * floor stays on stock Ubuntu 22.04 (ffmpeg 4.4 + GStreamer 1.20), and the Wayland portal lifecycle
 * (D-Bus session held open across the recording) is sufficiently different from the stateless
 * ffmpeg argv shape that a separate recorder reads cleaner.
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
        // into the primary screen's coordinate space (or use `ScreenCaptureKitRecorder`
        // window capture on macOS, which addresses windows by id rather than screen region).
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

    /**
     * Builds the argv for a Windows region capture via the gdigrab device.
     *
     * gdigrab carves the region on the *input* side via `-offset_x`, `-offset_y`, and
     * `-video_size`, so no `crop` filter is needed. Negative offsets are accepted: Windows'
     * virtual-desktop coordinate space puts non-primary monitors at negative coordinates if they're
     * positioned to the left of (or above) the primary display, and gdigrab's docs explicitly
     * support that case.
     *
     * [RecordingOptions.screenIndex] is intentionally unused here — gdigrab targets the entire
     * virtual desktop with `-i desktop`, and callers select a specific monitor by translating its
     * bounds into the region origin.
     */
    fun gdigrabRegionCapture(
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
            add("-loglevel")
            add("warning")
            add("-y")
            // gdigrab input options must precede `-i`. -framerate is the capture rate;
            // -draw_mouse toggles cursor compositing on the captured frames; -offset_x /
            // -offset_y / -video_size select the region on the input side, so we don't need
            // a crop filter and can't fall into avfoundation's silent-clamp pitfall.
            add("-f")
            add("gdigrab")
            add("-framerate")
            add(options.frameRate.toString())
            add("-draw_mouse")
            add(if (options.captureCursor) "1" else "0")
            add("-offset_x")
            add(region.x.toString())
            add("-offset_y")
            add(region.y.toString())
            add("-video_size")
            add("${region.width}x${region.height}")
            add("-i")
            add("desktop")
            add("-c:v")
            add(options.codec)
            add(output.toString())
        }
    }

    /**
     * Builds the argv for a Windows title-based window capture via the gdigrab device.
     *
     * Title matching is exact and case-sensitive, and the window must be visible (not minimised).
     * For Compose Desktop top-level windows this works. Jewel-in-IDE tool windows have no top-level
     * title to match against — callers in that scenario must fall back to [gdigrabRegionCapture]
     * using the panel's screen bounds.
     */
    fun gdigrabWindowCapture(
        ffmpegPath: Path,
        windowTitle: String,
        output: Path,
        options: RecordingOptions,
    ): List<String> {
        require(windowTitle.isNotBlank()) {
            "windowTitle must not be blank — gdigrab's `title=` form treats an empty title as " +
                "the desktop and would record the wrong surface"
        }
        return buildList {
            add(ffmpegPath.toString())
            add("-loglevel")
            add("warning")
            add("-y")
            add("-f")
            add("gdigrab")
            add("-framerate")
            add(options.frameRate.toString())
            add("-draw_mouse")
            add(if (options.captureCursor) "1" else "0")
            add("-i")
            // The full string after `title=` is the match key, including any spaces. Each argv
            // element is a separate token, so we don't need (and must not add) shell quoting.
            add("title=$windowTitle")
            add("-c:v")
            add(options.codec)
            add(output.toString())
        }
    }

    /**
     * Builds the argv for a Linux X11 region capture via the x11grab device.
     *
     * x11grab takes the display + offset baked into the input URL (`<display>+x,y`) and the size
     * via `-video_size`, mirroring gdigrab's input-side approach. No crop filter, no silent-clamp
     * pitfall. Negative offsets are accepted: X11 multi-monitor setups can position monitors at any
     * coordinate within the screen's pixel space, and ffmpeg's x11grab is documented to support
     * negative offsets up to the display's `XDisplayWidth`/`XDisplayHeight` bounds.
     *
     * [displayName] is the X display selector, normally read from the `DISPLAY` env var (e.g. `:0`,
     * `:0.0`, `:1`). Callers from Spectre go through [FfmpegBackend.LinuxX11Grab] which handles the
     * env read; this function takes it explicitly so the argv stays a pure function of its inputs.
     *
     * X11-only: a Wayland session without XWayland produces ffmpeg "cannot open display" at spawn
     * time. Wayland-native capture (PipeWire + xdg-desktop-portal) is a separate backend.
     *
     * [RecordingOptions.screenIndex] is intentionally unused here — X11 multi-monitor presents as a
     * single combined display, and the region's origin already encodes which monitor is targeted.
     */
    fun x11grabRegionCapture(
        ffmpegPath: Path,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
        displayName: String,
    ): List<String> {
        require(region.width > 0 && region.height > 0) {
            "region must have positive dimensions, was ${region.width}x${region.height}"
        }
        require(displayName.isNotBlank()) {
            "displayName must not be blank — pass the X display selector (e.g. \":0\", \":0.0\")"
        }
        return buildList {
            add(ffmpegPath.toString())
            add("-loglevel")
            add("warning")
            add("-y")
            // x11grab input options must precede `-i`. -framerate is the capture rate;
            // -draw_mouse toggles cursor compositing on the captured frames; -video_size sets
            // the captured area dimensions. The offset goes into the `-i` URL after `+`.
            add("-f")
            add("x11grab")
            add("-framerate")
            add(options.frameRate.toString())
            add("-draw_mouse")
            add(if (options.captureCursor) "1" else "0")
            add("-video_size")
            add("${region.width}x${region.height}")
            add("-i")
            // x11grab's URL form is `<display>+<x>,<y>` (e.g. `:0.0+100,200`). The offset is
            // part of the input URL itself, not a separate `-offset_x`/`-offset_y` pair like
            // gdigrab — that's an x11grab-specific quirk in ffmpeg's input device API.
            add("$displayName+${region.x},${region.y}")
            add("-c:v")
            add(options.codec)
            add(output.toString())
        }
    }
}

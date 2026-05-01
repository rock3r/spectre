package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Builds `gst-launch-1.0` argv lists for Wayland-side recording via the PipeWire stream that
 * `xdg-desktop-portal` hands us. The pure-function shape mirrors [FfmpegCli] — no I/O, no process
 * spawn — so the produced pipeline is unit-testable in isolation. The actual subprocess lifecycle
 * lives in `dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder`.
 *
 * Why GStreamer (and not ffmpeg's `pipewiregrab` device, which was added in ffmpeg 6.1):
 * - GStreamer 1.20 ships on stock Ubuntu 22.04 and has had `pipewiresrc` since 1.18 (early 2020),
 *   so we don't need to bump the supported floor or pull from a third-party PPA / static build to
 *   validate the path.
 * - GStreamer is the canonical Linux media stack — GNOME Shell, Loupe, Showtime, OBS Studio, and
 *   Firefox all use it for screen-cast consumption on Wayland. ffmpeg's PipeWire device is newer
 *   and less battle-tested in this exact role.
 * - The PipeWire portal session is held open by the JVM-side D-Bus connection regardless of which
 *   encoder we spawn, so the choice doesn't affect the portal lifecycle.
 *
 * Requires `gst-launch-1.0` plus the `pipewiresrc` plugin (in `gstreamer1.0-plugins-bad` on
 * Debian/Ubuntu) and the H.264 encoder + MP4 muxer plugins (`gstreamer1.0-plugins-ugly` for
 * `x264enc`, `gstreamer1.0-plugins-good` for `mp4mux`).
 * `WaylandPortalRecorder.resolveGstLaunchPath()` performs the binary lookup; missing plugins
 * surface as a non-zero exit during the start probe.
 */
internal object GstCli {

    /**
     * Builds the gst-launch-1.0 pipeline that consumes the PipeWire screen-cast stream identified
     * by [pipewireNodeId] and writes a cropped, x264-encoded MP4 to [output].
     *
     * The crop is applied via the `videocrop` element (which uses the `top`/`bottom`/`left`/
     * `right` form of pixel insets, NOT a `crop=W:H:X:Y` rectangle). To get a cropped frame of
     * `[width × height]` starting at `(x, y)` from a stream of dimensions `(streamWidth ×
     * streamHeight)`, we pass:
     * - `left = region.x`
     * - `top = region.y`
     * - `right = streamWidth - (region.x + region.width)`
     * - `bottom = streamHeight - (region.y + region.height)`
     *
     * [streamSize] is the PipeWire stream's frame dimensions as returned by the portal's
     * `Start.Response` `streams` payload (each stream has a `size` property). Without it we
     * couldn't compute the right/bottom insets.
     *
     * Lifecycle assumptions for the caller:
     * - The PipeWire connection inside `pipewiresrc` is created on demand the first time the
     *   element rolls to PLAYING. It uses the user's `$XDG_RUNTIME_DIR/pipewire-0` socket and the
     *   per-user PipeWire daemon, which the portal grant has just authorised.
     * - The `-e` flag on `gst-launch-1.0` (sent via the [eosOnExit] knob) registers an EOS handler
     *   so a SIGTERM propagates as End-Of-Stream through the pipeline. The mux gets a chance to
     *   write the MOOV atom, leaving a playable file. WITHOUT `-e`, SIGTERM kills gst-launch dead
     *   and the file is unfinalised. Tests can disable it for argv-shape assertions but production
     *   use should leave it on.
     */
    fun pipewireRegionCapture(
        gstLaunchPath: Path,
        pipewireNodeId: Int,
        region: Rectangle,
        streamSize: Pair<Int, Int>,
        output: Path,
        options: RecordingOptions,
        eosOnExit: Boolean = true,
    ): List<String> {
        require(region.width > 0 && region.height > 0) {
            "region must have positive dimensions, was ${region.width}x${region.height}"
        }
        require(region.x >= 0 && region.y >= 0) {
            "region origin must be non-negative for the videocrop filter, was " +
                "(${region.x}, ${region.y})"
        }
        require(pipewireNodeId >= 0) {
            "pipewireNodeId must be a valid PipeWire node id (>= 0), was $pipewireNodeId"
        }
        val (streamWidth, streamHeight) = streamSize
        require(streamWidth > 0 && streamHeight > 0) {
            "streamSize must have positive dimensions, was ${streamWidth}x$streamHeight"
        }
        require(region.x + region.width <= streamWidth) {
            "region right edge ${region.x + region.width} exceeds stream width $streamWidth"
        }
        require(region.y + region.height <= streamHeight) {
            "region bottom edge ${region.y + region.height} exceeds stream height $streamHeight"
        }
        val left = region.x
        val top = region.y
        val right = streamWidth - (region.x + region.width)
        val bottom = streamHeight - (region.y + region.height)
        return buildList {
            add(gstLaunchPath.toString())
            // EOS-on-exit: SIGTERM → End-Of-Stream → mux finalises → file plays back. Without
            // this, the mux MOOV atom never lands and the file is half-written.
            if (eosOnExit) add("-e")
            // gst-launch-1.0 parses its argv element-by-element — each pipeline token is its
            // own arg, including the `!` link separators. That's why we can't just join the
            // pipeline as one string the way `bash -c` would let us; ProcessBuilder hands argv
            // straight to exec() with no shell tokenisation.
            //
            // pipewiresrc: do-timestamp keeps buffer PTS monotonic so the encoder/mux don't
            // choke on early-arrival jitter; path=<node_id> picks the stream the portal
            // granted; always-copy=true avoids occasional reuse-of-buffer races we've seen
            // when the PipeWire core is busy.
            add("pipewiresrc")
            add("do-timestamp=true")
            add("path=$pipewireNodeId")
            add("always-copy=true")
            add("!")
            // Force a fixed framerate before encoding so the muxer's CFR assumption holds.
            add("videorate")
            add("!")
            add("video/x-raw,framerate=${options.frameRate}/1")
            add("!")
            // videocrop uses pixel-inset form (top/bottom/left/right), not a (W,H,X,Y)
            // rectangle. We translated to insets above.
            add("videocrop")
            add("top=$top")
            add("bottom=$bottom")
            add("left=$left")
            add("right=$right")
            add("!")
            add("videoconvert")
            add("!")
            // tune=zerolatency keeps the encoder out of B-frame land (no reorder buffer);
            // speed-preset=ultrafast prioritises CPU over compression — capture-time perf
            // matters more than file size for test recordings.
            add("x264enc")
            add("tune=zerolatency")
            add("speed-preset=ultrafast")
            add("!")
            // h264parse before the mux fixes up the byte-stream → AVCC sample-entry switch
            // that mp4mux requires. Without it, mp4mux refuses the input.
            add("h264parse")
            add("!")
            // faststart=true rewrites the file at EOS so the moov atom lands at the front —
            // playable while still streaming, and friendlier to the test artefact downloaders
            // that don't fully buffer before parsing.
            add("mp4mux")
            add("faststart=true")
            add("!")
            add("filesink")
            // location= takes a single token; ProcessBuilder won't double-quote, gst-launch
            // takes everything after the `=` as the property value verbatim.
            add("location=${output.toString()}")
        }
    }
}

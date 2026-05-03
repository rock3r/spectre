//! `gst-launch-1.0` argv builder for the PipeWire screen-cast pipeline. Pure function — no
//! I/O, no syscalls — so unit tests can pin the argv shape without a running PipeWire
//! daemon.
//!
//! Pipeline shape:
//! ```text
//! pipewiresrc fd=$FD path=$NODE do-timestamp=true always-copy=true
//!   ! videorate
//!   ! video/x-raw,framerate=$FPS/1
//!   ! videocrop top=$T bottom=$B left=$L right=$R
//!   ! videoconvert
//!   ! x264enc tune=zerolatency speed-preset=ultrafast
//!   ! h264parse
//!   ! mp4mux faststart=true
//!   ! filesink location=$OUTPUT
//! ```
//!
//! Why each piece:
//! - `pipewiresrc fd=$FD path=$NODE` — `fd` selects the PipeWire core (must be the FD from
//!   `OpenPipeWireRemote` for the portal grant; `fd=-1` falls back to the user's default
//!   socket and silently produces 0 frames for portal-granted nodes). `path` picks the
//!   specific stream node within that core.
//! - `do-timestamp=true` — keeps buffer PTS monotonic so the encoder/mux don't choke on
//!   early-arrival jitter when the stream first starts.
//! - `always-copy=true` — avoids occasional reuse-of-buffer races we've seen when the
//!   PipeWire core is busy.
//! - `videorate ! video/x-raw,framerate=N/1` — pin a fixed CFR before encoding so the muxer's
//!   timestamp assumptions hold.
//! - `videocrop top/bottom/left/right` — pixel-inset form (NOT `crop=W:H:X:Y`). The
//!   translation from `(x, y, w, h)` rectangle is documented in [`build_pipewire_argv`].
//! - `x264enc tune=zerolatency speed-preset=ultrafast` — keep the encoder out of B-frame
//!   land (no reorder buffer); prioritise CPU over compression — capture-time perf matters
//!   more than file size.
//! - `h264parse` — fixes up the byte-stream → AVCC sample-entry switch that mp4mux requires.
//! - `mp4mux faststart=true` — rewrites the file at EOS so the moov atom lands at the front,
//!   playable while still streaming.
//! - `filesink location=$OUTPUT` — writes the encoded mp4 to disk.

use crate::protocol::Region;
use anyhow::{bail, Result};
use std::path::Path;

/// Build the argv list for `gst-launch-1.0` consuming the portal-granted PipeWire stream.
/// Always emits `-e` (EOS-on-SIGTERM) — the recorder lifecycle relies on SIGTERM for clean
/// shutdown.
///
/// `region == Some(...)` selects region-capture mode: the rectangle is interpreted relative to
/// the PipeWire stream's own coordinate space (so `(0, 0)` is the top-left of the granted
/// monitor's pixel area; pre-translation from AWT-screen coords to stream-relative happens in
/// [`crate::recorder::run`]). The pipeline emits a `videocrop` element with pixel insets
/// derived from the rectangle.
///
/// `region == None` selects window-targeted capture (#85): no `videocrop`, the encoder consumes
/// the entire PipeWire stream as-is. The granted stream is already scoped to the user-picked
/// window, and the compositor re-aims it as the window moves — adding our own crop would fight
/// that and silently miss pixels off the original rectangle.
pub fn build_pipewire_argv(
    pipewire_node_id: u32,
    pipewire_fd: i32,
    region: Option<Region>,
    stream_size: (u32, u32),
    frame_rate: u32,
    capture_cursor: bool,
    output: &Path,
    codec: &str,
) -> Result<Vec<String>> {
    if pipewire_fd < 0 {
        bail!(
            "pipewire_fd must be a valid Unix FD (>= 0), was {}. Did OpenPipeWireRemote \
             return a valid descriptor?",
            pipewire_fd
        );
    }
    let (stream_w, stream_h) = stream_size;
    if stream_w == 0 || stream_h == 0 {
        bail!(
            "stream_size must have positive dimensions, was {}x{}",
            stream_w,
            stream_h
        );
    }
    // Validate region domain when present. None is the documented "no crop, record the whole
    // stream" mode for window-targeted capture and bypasses these checks entirely.
    let crop_insets = if let Some(region) = region {
        // Region fields are i32 on both sides of the wire (matches `java.awt.Rectangle` shape).
        // `Rectangle` doesn't enforce non-negative widths/heights, so a misbehaving caller could
        // send (or compute) negatives. We catch both the negative case and the zero case with one
        // domain check and a single clear error message, rather than relying on the JSON
        // deserialiser to reject — see the `Region` doc-comment in protocol.rs for why.
        if region.width <= 0 || region.height <= 0 {
            bail!(
                "region must have positive dimensions, was {}x{}",
                region.width,
                region.height
            );
        }
        if region.x < 0 || region.y < 0 {
            bail!(
                "region origin must be non-negative for the videocrop filter, was ({}, {})",
                region.x,
                region.y
            );
        }
        let region_right = region.x as i64 + region.width as i64;
        let region_bottom = region.y as i64 + region.height as i64;
        if region_right > stream_w as i64 {
            bail!(
                "region right edge {} exceeds stream width {}",
                region_right,
                stream_w
            );
        }
        if region_bottom > stream_h as i64 {
            bail!(
                "region bottom edge {} exceeds stream height {}",
                region_bottom,
                stream_h
            );
        }
        let top = region.y as u32;
        let bottom = stream_h - region_bottom as u32;
        let left = region.x as u32;
        let right = stream_w - region_right as u32;
        Some((top, bottom, left, right))
    } else {
        None
    };
    // Cursor is rendered into the captured frames by PipeWire (cursor_mode=EMBEDDED on
    // SelectSources). gst-launch's pipewiresrc has no per-element cursor knob — the
    // capture_cursor param is consumed at portal-handshake time, not here. We accept it on
    // this signature for symmetry with the FfmpegCli sibling builders.
    let _ = capture_cursor;

    let mut argv = vec![
        "gst-launch-1.0".to_string(),
        "-e".to_string(),
        "pipewiresrc".to_string(),
        format!("fd={pipewire_fd}"),
        format!("path={pipewire_node_id}"),
        "do-timestamp=true".to_string(),
        "always-copy=true".to_string(),
        "!".to_string(),
        "videorate".to_string(),
        "!".to_string(),
        format!("video/x-raw,framerate={frame_rate}/1"),
        "!".to_string(),
    ];
    if let Some((top, bottom, left, right)) = crop_insets {
        argv.extend([
            "videocrop".to_string(),
            format!("top={top}"),
            format!("bottom={bottom}"),
            format!("left={left}"),
            format!("right={right}"),
            "!".to_string(),
        ]);
    }
    argv.extend(["videoconvert".to_string(), "!".to_string()]);
    // x264 is the default codec. Other values are passed through unmodified — accepting any
    // GStreamer encoder name here (e.g. `x265enc` for HEVC, `vaapih264enc` for hardware-
    // accelerated H.264) without re-coding tuning flags.
    if codec == "libx264" || codec == "x264enc" {
        argv.extend([
            "x264enc".to_string(),
            "tune=zerolatency".to_string(),
            "speed-preset=ultrafast".to_string(),
            "!".to_string(),
            "h264parse".to_string(),
        ]);
    } else {
        argv.push(codec.to_string());
    }
    argv.extend([
        "!".to_string(),
        "mp4mux".to_string(),
        "faststart=true".to_string(),
        "!".to_string(),
        "filesink".to_string(),
        format!("location={}", output.display()),
    ]);
    Ok(argv)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::Region;
    use std::path::PathBuf;

    fn region(x: i32, y: i32, w: i32, h: i32) -> Option<Region> {
        Some(Region {
            x,
            y,
            width: w,
            height: h,
        })
    }

    fn argv_for_default() -> Vec<String> {
        build_pipewire_argv(
            42,
            17,
            region(100, 200, 640, 480),
            (1920, 1080),
            30,
            true,
            &PathBuf::from("/tmp/spectre/out.mp4"),
            "libx264",
        )
        .unwrap()
    }

    fn argv_for_window_targeted() -> Vec<String> {
        // #85: window-targeted capture sends `region: None` so the helper records the entire
        // PipeWire stream as-is. The compositor follows the picked window across the screen;
        // any post-portal crop would silently drop pixels off the original rectangle.
        build_pipewire_argv(
            42,
            17,
            None,
            (640, 480),
            30,
            true,
            &PathBuf::from("/tmp/spectre/window.mp4"),
            "libx264",
        )
        .unwrap()
    }

    fn assert_contains_sequence(argv: &[String], expected: &[&str]) {
        let found = argv
            .windows(expected.len())
            .any(|w| w.iter().zip(expected).all(|(a, b)| a == b));
        assert!(
            found,
            "expected {expected:?} to appear contiguously in {argv:?}"
        );
    }

    #[test]
    fn argv_starts_with_gst_launch_and_eos_flag() {
        let argv = argv_for_default();
        assert_eq!(argv[0], "gst-launch-1.0");
        assert_eq!(argv[1], "-e", "must always pass -e for clean SIGTERM/EOS");
    }

    #[test]
    fn argv_passes_pipewire_fd_and_node_id_to_pipewiresrc() {
        let argv = argv_for_default();
        assert_contains_sequence(&argv, &["pipewiresrc", "fd=17", "path=42"]);
    }

    #[test]
    fn argv_translates_region_to_videocrop_pixel_insets() {
        // (100, 200) origin + 640x480 inside 1920x1080 stream:
        //   top=200  bottom=1080-(200+480)=400
        //   left=100 right=1920-(100+640)=1180
        let argv = argv_for_default();
        assert_contains_sequence(
            &argv,
            &["videocrop", "top=200", "bottom=400", "left=100", "right=1180"],
        );
    }

    #[test]
    fn argv_pins_framerate_before_encoder() {
        let argv = build_pipewire_argv(
            42,
            17,
            region(100, 200, 640, 480),
            (1920, 1080),
            60,
            true,
            &PathBuf::from("/tmp/out.mp4"),
            "libx264",
        )
        .unwrap();
        assert_contains_sequence(&argv, &["videorate", "!", "video/x-raw,framerate=60/1"]);
    }

    #[test]
    fn argv_uses_x264enc_with_low_latency_tuning_for_libx264() {
        let argv = argv_for_default();
        assert_contains_sequence(
            &argv,
            &["x264enc", "tune=zerolatency", "speed-preset=ultrafast"],
        );
    }

    #[test]
    fn argv_writes_faststart_mp4_to_filesink_location() {
        let argv = argv_for_default();
        assert_contains_sequence(
            &argv,
            &[
                "mp4mux",
                "faststart=true",
                "!",
                "filesink",
                "location=/tmp/spectre/out.mp4",
            ],
        );
    }

    #[test]
    fn argv_rejects_zero_or_negative_region_dimensions() {
        let bad_w_zero = build_pipewire_argv(
            42, 17, region(0, 0, 0, 100), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(bad_w_zero.is_err(), "zero width should be rejected");
        let bad_h_zero = build_pipewire_argv(
            42, 17, region(0, 0, 100, 0), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(bad_h_zero.is_err(), "zero height should be rejected");

        // Region.{width,height} are signed i32 to match the JVM-side `Rectangle` shape, so
        // a misbehaving caller could send negative dimensions across the wire. The single
        // domain check rejects both at once with a clear error rather than letting them
        // wrap into giant unsigned values further down the videocrop math.
        let bad_w_neg = build_pipewire_argv(
            42, 17, region(0, 0, -10, 100), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(bad_w_neg.is_err(), "negative width should be rejected");
        let bad_h_neg = build_pipewire_argv(
            42, 17, region(0, 0, 100, -5), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(bad_h_neg.is_err(), "negative height should be rejected");
    }

    #[test]
    fn argv_rejects_negative_region_origin() {
        // videocrop's pixel-inset form would underflow if origin < 0, producing nonsense
        // crops. Reject up-front so a misconfigured caller doesn't silently produce a
        // black mp4 the way the JVM/JNR-POSIX bake-off attempt did.
        let bad_x = build_pipewire_argv(
            42, 17, region(-1, 0, 100, 100), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(bad_x.is_err(), "negative x should be rejected");
        let bad_y = build_pipewire_argv(
            42, 17, region(0, -10, 100, 100), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(bad_y.is_err(), "negative y should be rejected");
    }

    #[test]
    fn argv_rejects_region_exceeding_stream_bounds() {
        let too_wide = build_pipewire_argv(
            42, 17, region(0, 0, 2000, 100), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(too_wide.is_err(), "region wider than stream should be rejected");
        let too_tall = build_pipewire_argv(
            42, 17, region(0, 0, 100, 2000), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(too_tall.is_err(), "region taller than stream should be rejected");
    }

    #[test]
    fn argv_omits_videocrop_for_window_targeted_capture() {
        // None region → no `videocrop` element. The pipeline goes straight from the framerate
        // caps filter to videoconvert, leaving the stream's pixels untouched so the compositor
        // can re-aim the window-targeted PipeWire stream as the user moves the window.
        let argv = argv_for_window_targeted();
        assert!(
            !argv.contains(&"videocrop".to_string()),
            "argv must not contain a videocrop element when region is None: {argv:?}"
        );
        // The framerate cap → videoconvert hand-off must remain intact even without the crop.
        assert_contains_sequence(&argv, &["video/x-raw,framerate=30/1", "!", "videoconvert"]);
    }

    #[test]
    fn argv_keeps_pipewire_and_encoder_for_window_targeted_capture() {
        // Sanity: the rest of the pipeline (pipewiresrc, x264enc tuning, mp4mux faststart)
        // is identical between region- and window-targeted modes.
        let argv = argv_for_window_targeted();
        assert_contains_sequence(&argv, &["pipewiresrc", "fd=17", "path=42"]);
        assert_contains_sequence(
            &argv,
            &["x264enc", "tune=zerolatency", "speed-preset=ultrafast"],
        );
        assert_contains_sequence(
            &argv,
            &[
                "mp4mux",
                "faststart=true",
                "!",
                "filesink",
                "location=/tmp/spectre/window.mp4",
            ],
        );
    }

    #[test]
    fn argv_rejects_negative_pipewire_fd() {
        // -1 is the "default user socket" sentinel for pipewiresrc — useful for
        // unauthenticated reads but a guaranteed 0-byte recording for portal-granted
        // nodes. Reject up-front so a misconfigured caller doesn't silently produce a
        // black mp4.
        let bad = build_pipewire_argv(
            42, -1, region(0, 0, 100, 100), (1920, 1080), 30, true,
            &PathBuf::from("/tmp/x"), "libx264",
        );
        assert!(bad.is_err(), "fd=-1 should be rejected (would produce 0-byte mp4)");
    }
}

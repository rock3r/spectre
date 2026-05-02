# Recording limitations

The `recording` module ships:
- `FfmpegRecorder` — region capture via `ffmpeg` against the platform's native capture device.
  Three backends auto-selected from `os.name`: `avfoundation` (macOS), `gdigrab` (Windows),
  `x11grab` (Linux Xorg). Trade-offs below.
- `ScreenCaptureKitRecorder` (#18 — landed) — macOS-only window-targeted capture via a bundled
  Swift helper. Removes the "anything overlapping the region appears in the recording" class
  of problems for top-level windows. Falls outside the region-capture trade-off list below; see
  the recording module README for usage.
- `FfmpegWindowRecorder` (#22 / #55 — landed) — Windows-only title-based window capture via
  `gdigrab title=`. Mirrors the SCK ergonomics for Windows top-level Compose windows.

The rest of this document describes the region-capture path (relevant on every platform when
there's no host window to target). Use ScreenCaptureKit / FfmpegWindowRecorder when you have a
top-level `ComposeWindow` you want to record cleanly; use region capture for embedded
`ComposePanel` surfaces or arbitrary screen rectangles.

## Platform

- **macOS** — `avfoundation` region capture. Requires the Screen Recording permission.
- **Windows** — `gdigrab` region capture (#22). Plus title-based window capture via
  `FfmpegWindowRecorder` (#55).
- **Linux Xorg sessions** — `x11grab` region capture (#75 / #76). Reads `DISPLAY`. Routine
  validation has only been on Ubuntu 22.04's Xorg session (one machine, one X server build)
  and on CI under `xvfb-run` (Xorg protocol over a virtual framebuffer, no GPU). Other
  Xorg WMs / distros fall under the "Linux is best-effort, contributions welcome" line in
  the README.
- **Linux Wayland sessions** — `gst-launch-1.0` driven through the
  `xdg-desktop-portal` ScreenCast interface, with the PipeWire FD passed to the encoder by a
  small Rust helper binary (`spectre-wayland-helper`, sources at
  `recording/native/linux/`). The JVM-side `WaylandPortalRecorder` spawns the helper and
  talks to it over stdin/stdout via newline-delimited JSON. **First call within a session
  pops the compositor's "share your screen" dialog**; subsequent calls reuse the grant via
  the portal's `restore_token`. Why the helper: the JVM-only attempt (#77 stage 2) hit a
  dbus-java UnixFD-unmarshalling bug that wasn't fixable trivially, and Rust's `std::process`
  makes FD inheritance into `gst-launch` a one-liner where the JVM's `ProcessBuilder`
  doesn't expose the necessary `fcntl(F_SETFD, ...)` knob. The helper-as-subprocess shape
  also matches the macOS SCK helper (`recording/native/macos/`) — same pattern, same
  bundling, same recorder-skeleton on the JVM side.

  Validated end-to-end on Ubuntu 22.04 / GNOME 42 / mutter (real-pixel mp4 with the smoke
  runner, 2026-05-02). KDE / Plasma, sway, wlroots-based compositors, non-Ubuntu distros,
  and other Ubuntu versions aren't part of the routine validation matrix yet — the
  xdg-desktop-portal interface is standardised across compositors so most should "just
  work", but bug reports are how we'll find out. See the README for the contribution invite.

  **Frame-rate fidelity tracks what the compositor delivers.** The pipeline runs the
  PipeWire stream through a `videorate` element clamped to `RecordingOptions.frameRate`
  (default 30). When the source delivers fewer frames than the target, `videorate` pads
  the gaps by duplicating the last frame; when it delivers more, the excess gets dropped.
  On real hardware with GPU-side compositor composition this is a no-op — the source
  comfortably sustains 30 fps and the output is byte-clean. On a Hyper-V / VirtualBox VM
  with a software-rendered virtual GPU, the source rate dips into the 5–25 fps range and
  the output mp4 contains visible duplicate-frame runs even though the file metadata
  reports a flat 30 fps. This is faithful capture, not a recording bug — the compositor
  genuinely had no new frame to deliver during those gaps. If your scenario captures from
  a VM and the visible stutter matters more than the frame-rate metadata, lower
  `RecordingOptions.frameRate` to match what your VM actually produces (15 is usually a
  safe floor for software-rendered VM GPUs).

## Capture mode

- **Region capture, not window capture**. v1 records a fixed `Rectangle` of the virtual desktop —
  whatever pixels the screen happens to be showing inside that region land in the file. The region
  is bound at `Recorder.start(...)` time and does not follow a window; the v2 work surfaces a
  proper window-targeted backend via ScreenCaptureKit.
- **Embedded `ComposePanel` surfaces always fall through to region capture.** Window-targeted
  capture is gated on a non-zero `windowHandle`, which Spectre populates only for top-level
  `ComposeWindow`s. A panel embedded inside an IntelliJ tool window, a `JFrame`, a `JDialog`, or a
  `SwingPanel` host inside Compose has `windowHandle == 0L` and gets the region path. Practical
  consequences:
  - Anything that visually overlaps the panel — other windows, the menu bar, OS notifications, a
    floating popup that escapes the panel's bounds — appears in the recording.
  - The captured region is the panel's screen-space bounds at start. If the host window moves or
    resizes while recording, the panel's pixels drift out of the captured rectangle and you record
    whatever is now under the original rectangle (often empty desktop).
  - Off-screen panels record black frames.

## What window movement / popups do during a recording

- **Window movement isn't followed**. Move the host window after `start(...)` and the recording
  keeps capturing the original screen rectangle. Stop and restart to follow the new position.
- **Popups that escape the captured region are partially clipped**. Compose Desktop's `OnWindow`
  popup layer renders in a separate top-level OS window; if that popup pops up outside the recorded
  rectangle the recording will not include it. `OnSameCanvas` and `OnComponent` popups stay inside
  the panel/window bounds and are recorded as long as the panel itself is.

## HiDPI / Retina

- The recorded resolution is the **screen-pixel** size of the region, not the dp size. A 400×300dp
  region on a 2× display becomes an 800×600 pixel video.
- `Rectangle` coordinates passed to `start(...)` are in screen pixels. If you derive the region
  from `AutomatorNode.boundsOnScreen` you get the right thing for free; if you derive it from
  `boundsInWindow` you have to apply the density yourself.

## Permissions and process lifecycle

- The JVM process needs **macOS Screen Recording permission** (`MacOsRecordingPermissions`
  documents the path). Without it, `ffmpeg` exits during the startup probe and `Recorder.start`
  surfaces an error rather than handing back a handle.
- The `ffmpeg` subprocess is spawned eagerly: by the time `start(...)` returns, frames are
  landing in the output file. A failure to spawn (binary not on PATH, codec unavailable, AVFoundation
  device busy) surfaces as an exception from `start(...)`, not as a silent "successful" handle.
- The handle MUST be stopped (`RecordingHandle.stop(...)`) for the file to be flushed cleanly. A
  JVM exit without stop leaves a partial / non-finalised file and an orphaned subprocess.

## What's NOT a v1 limitation

- Cursor capture is configurable via `RecordingOptions.captureCursor` and works under the region
  path. The cursor pixels are baked into the frames; there is no overlay you can toggle in
  post-processing.
- Audio capture is intentionally absent — v1 records video only. Adding audio is a follow-up.

## What v2 changed

- Window-targeted capture via `ScreenCaptureKitRecorder` (#18) is now the recommended path for
  top-level `ComposeWindow` surfaces. It removes the "anything overlapping the region appears
  in the video" class of problems and follows the window across the screen.
- The embedded-panel path is still region capture only — there's no host window for SCK to
  target. A future "render the panel into a frame buffer and feed `ffmpeg` on stdin" backend
  (the synthetic-screenshot path in `SyntheticRobotAdapter` is the blueprint) would close that
  gap; not currently scoped.

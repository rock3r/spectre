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
- **Linux Wayland sessions** — **partial: handshake works, encoder spawn doesn't yet.** `LinuxX11Grab`
  detects Wayland (via `XDG_SESSION_TYPE`, `WAYLAND_DISPLAY`, or a `wayland-*` socket in
  `XDG_RUNTIME_DIR`) and routes through `WaylandPortalRecorder` instead of producing the
  uniform-black frames `x11grab`-through-XWayland would. The xdg-desktop-portal handshake
  (`CreateSession` → `SelectSources` → `Start`) completes cleanly via dbus-java; the recorder
  extracts a PipeWire stream node id from the response. **What it doesn't do** — yet — is spawn
  the gst-launch encoder against that node. The portal's permission model is FD-scoped:
  pipewiresrc reads the granted node only when given the file descriptor returned by
  `OpenPipeWireRemote`, and the JDK's `ProcessBuilder` doesn't inherit arbitrary FDs across
  exec. Without the FD, gst-launch reaches PLAYING but receives zero frames and the output
  mp4 is 0 bytes. To avoid the silent-corruption antipattern, `WaylandPortalRecorder.start`
  throws an `UnsupportedOperationException` after the handshake completes with an actionable
  message naming the granted node + size. Workarounds in the meantime: switch the session to
  Xorg (`WaylandEnable=false` in `/etc/gdm3/custom.conf` + `systemctl restart gdm`, or pick
  "Ubuntu on Xorg" at GDM), or run under Xvfb. Stage 3 (the JNR-POSIX-based FD inheritance
  plumbing that closes the loop) is tracked under
  [#80](https://github.com/rock3r/spectre/issues/80).

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

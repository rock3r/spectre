# Recording limitations (v1)

Spectre's v1 recording (`recording` module) shells out to a system `ffmpeg` binary using
`avfoundation` on macOS. This is deliberately the simplest possible capture path so v1 can ship
quickly; the trade-offs below are the known consequences. Most of them go away under the
window-targeted ScreenCaptureKit backend tracked in v2 (#18).

## Platform

- **macOS only**. v1 implements `avfoundation` region capture and nothing else.
- **Windows** (`gdigrab`) is deferred to v3.
- **Linux** (`x11grab` / Wayland) is deferred to v4.

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

## Pointers for v2

- Window-targeted capture via ScreenCaptureKit (#18) removes the "anything overlapping the region
  appears in the video" class of problems for top-level windows.
- A separate path for embedded panels would need to render the panel into a frame buffer and feed
  that to `ffmpeg` on stdin (the synthetic-screenshot path in `SyntheticRobotAdapter` is the
  blueprint). Tracked outside v1.

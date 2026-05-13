# Recording limitations

## Two recording modes

Before reading the rest of this page, it's worth being explicit about what "recording"
means here. The `recording` module exposes two structurally different ways of producing
a video file, and each has its own limitations:

- **Region capture** — records a fixed `Rectangle` of the virtual desktop, frame by
  frame. The source of pixels is the OS framebuffer (or the platform's nearest
  equivalent — `avfoundation` on macOS, `gdigrab` on Windows, `x11grab` on Linux Xorg,
  or the Wayland compositor's PipeWire stream on Linux Wayland). Whatever is showing
  on screen inside that rectangle goes into the file, regardless of which window is
  there. Region capture is the only path available when the Compose surface has no
  adaptable top-level OS window, such as a `ComposePanel` inside an IntelliJ tool
  window.
- **Window-targeted capture** — captures a specific OS window's pixels directly, not
  the screen rectangle the window happens to occupy. The source is the window's own
  backing store (`ScreenCaptureKit` on macOS) or the OS-level `gdigrab title=` capture
  on Windows. Because the source is the window itself, movement, occlusion, and
  off-screen position don't break the recording. This path is the right choice when
  you have a top-level `ComposeWindow`.

Backend → mode mapping:

| Backend                       | Mode                                                |
| ----------------------------- | --------------------------------------------------- |
| `FfmpegRecorder`              | Region capture (`avfoundation`/`gdigrab`/`x11grab`).|
| `WaylandPortalRecorder`       | Region capture, sourced from the Wayland portal (`SourceType.MONITOR`). |
| `WaylandPortalWindowRecorder` | Window-targeted (Linux Wayland, portal `SourceType.WINDOW` — only the picked window's pixels are captured). |
| `ScreenCaptureKitRecorder`    | Window-targeted (macOS).                            |
| `FfmpegWindowRecorder`        | Window-targeted (Windows, `gdigrab title=`).        |
| `AutoRecorder`                | Routes to the right one based on `TitledWindow?`.   |

The rest of this document is mostly about region capture's constraints, because that's
where the failure modes are. The window-targeted backends exist to sidestep those
failure modes, and the section below
([Window-targeted capture](#window-targeted-capture)) explains how they do it.

## Platform

- **macOS** — `avfoundation` region capture. Requires the Screen Recording permission.
- **Windows** — `gdigrab` region capture, plus title-based window capture via
  `FfmpegWindowRecorder`.
- **Linux Xorg sessions** — `x11grab` region capture. Reads `DISPLAY`. Routine
  validation has only been on Ubuntu 22.04's Xorg session (one machine, one X server build)
  and on CI under `xvfb-run` (Xorg protocol over a virtual framebuffer, no GPU). Other
  Xorg WMs/distros fall under the "Linux is best-effort, contributions welcome" line in
  the README.
- **Linux Wayland sessions** — `gst-launch-1.0` driven through the
  `xdg-desktop-portal` ScreenCast interface, with the PipeWire FD passed to the encoder by a
  small Rust helper binary (`spectre-wayland-helper`, sources at
  `recording/native/linux/`). Two JVM-side recorders share the helper:
  `WaylandPortalRecorder` (region-targeted, portal `SourceType.MONITOR` — user picks a
  monitor, helper crops to the requested rectangle) and `WaylandPortalWindowRecorder`
  (window-targeted, portal `SourceType.WINDOW` — user picks a specific window, the
  granted PipeWire stream contains only that window's pixels, the helper crops to the
  window's pixel size, #85). Both spawn the helper and talk to it over stdin/stdout via
  newline-delimited JSON. **First call within a session pops the compositor's
  "share your screen" dialog** (asking for a window or a monitor depending on which
  recorder is running); subsequent calls reuse the grant via the portal's `restore_token`.
  Why the helper: a pure-JVM attempt hit a dbus-java UnixFD-unmarshalling bug that wasn't
  fixable trivially, and Rust's `std::process` makes FD inheritance into `gst-launch` a
  one-liner where the JVM's `ProcessBuilder` doesn't expose the necessary
  `fcntl(F_SETFD, ...)` knob. The helper-as-subprocess shape also matches the macOS SCK
  helper (`recording/native/macos/`) — same pattern, same bundling, same recorder-skeleton
  on the JVM side.

  **Window-targeted Wayland needs `xprop` on `PATH`.** Mutter renders window-source-type
  streams with the WM-imposed invisible-shadow extents around the visible window —
  typically 25 px on each side for GTK CSD windows on GNOME — but AWT's
  `Frame.getBounds()` reports only the inner window. `WaylandPortalWindowRecorder` queries
  the X11 `_GTK_FRAME_EXTENTS(CARDINAL)` property on the JFrame's XWayland window via the
  system `xprop` binary to compute the right stream-relative crop; without it the
  close-button icon ends up clipped off the title bar. If `xprop` is not on `PATH` (it's
  part of `x11-utils` on Debian/Ubuntu — installed by default on the desktop image,
  separate package on minimal/server images), the WM doesn't publish
  `_GTK_FRAME_EXTENTS` (older Mutter, non-GTK CSD, server-side decorations like KDE
  Plasma's default), or the call to `WaylandPortalWindowRecorder.start` is given a
  `TitledWindow` with a null/blank title, the recorder throws `IllegalStateException`
  rather than producing a silently-misaligned recording. The fallback is to use
  `WaylandPortalRecorder` (region capture) — pass `window = null` to
  `AutoRecorder.start` and the router selects it automatically.

  Validated end-to-end on Ubuntu 22.04/GNOME 42/mutter, Ubuntu 24.04/GNOME 46/mutter, and
  Ubuntu 26.04/GNOME 50/mutter (real-pixel mp4 with the smoke runner, 2026-05-02 and
  2026-05-03 — the 24.04 run also confirmed `cursor_mode=Embedded` composites the system
  cursor into the captured frames when `RecordingOptions.captureCursor=true` (#87), and
  the 26.04 run confirmed window-source-type cropping produces a window-sized mp4 with
  no leakage from other apps and all WM decorations visible (#85)). KDE/Plasma, sway,
  wlroots-based compositors, non-Ubuntu distros, and other Ubuntu versions aren't part of
  the routine validation matrix yet — the xdg-desktop-portal interface is standardised
  across compositors but the `_GTK_FRAME_EXTENTS` query is GNOME/Mutter-specific, so
  window-targeted recording on KDE / sway will likely throw the
  "Could not determine WM frame extents" error and the caller should fall through to
  `WaylandPortalRecorder` until we wire compositor-specific frame-extent lookups. See
  the README for the contribution invite.

  **Frame-rate fidelity tracks what the compositor delivers.** The pipeline runs the
  PipeWire stream through a `videorate` element clamped to `RecordingOptions.frameRate`
  (default 30). When the source delivers fewer frames than the target, `videorate` pads
  the gaps by duplicating the last frame; when it delivers more, the excess gets dropped.
  On real hardware with GPU-side compositor composition this is a no-op — the source
  comfortably sustains 30 fps and the output is byte-clean. On a Hyper-V/VirtualBox VM
  with a software-rendered virtual GPU, the source rate dips into the 5–25 fps range and
  the output mp4 contains visible duplicate-frame runs even though the file metadata
  reports a flat 30 fps. This is faithful capture, not a recording bug — the compositor
  genuinely had no new frame to deliver during those gaps. If your scenario captures from
  a VM and the visible stutter matters more than the frame-rate metadata, lower
  `RecordingOptions.frameRate` to match what your VM actually produces (15 is usually a
  safe floor for software-rendered VM GPUs).

## Region capture

- **Region capture, not window capture**. Region capture records a fixed `Rectangle` of
  the virtual desktop — whatever pixels the screen happens to be showing inside that
  region land in the file. The region is bound at `Recorder.start(...)` time and does
  not follow a window. Use `ScreenCaptureKitRecorder` (macOS) or `FfmpegWindowRecorder`
  (Windows) when you have a top-level window to target — the next section covers what
  the window-targeted backends do differently.
- **Embedded `ComposePanel` surfaces without an adaptable top-level `Frame` fall
  through to region capture.**
  `AutoRecorder.start(window: TitledWindow?, region: Rectangle, …)` picks window-targeted
  capture only when `window` is non-null and (on Windows) has a non-blank title. The
  `Frame.asTitledWindow()` adapter exposes that title for any top-level `Frame`,
  including `ComposeWindow` and `JFrame` hosts. A panel embedded inside an IntelliJ
  tool window or a `SwingPanel` host inside Compose has no separate titled `Frame` to
  adapt — callers pass `window = null` and get the region path. Practical
  consequences:
  - Anything that visually overlaps the panel — other windows, the menu bar, OS notifications, a
    floating popup that escapes the panel's bounds — appears in the recording.
  - The captured region is the panel's screen-space bounds at start. If the host window moves or
    resizes while recording, the panel's pixels drift out of the captured rectangle and you record
    whatever is now under the original rectangle (often empty desktop).
  - Off-screen panels record black frames.

### Window movement and popups under region capture

These limitations are specific to the region path; the window-targeted backends below
solve them.

- **Window movement isn't followed.** Move the host window after `start(...)` and the
  recording keeps capturing the original screen rectangle. Stop and restart to follow
  the new position.
- **Popups that escape the captured region are clipped.** Compose Desktop's `OnWindow`
  popup layer renders in a separate top-level OS window; if that popup appears outside
  the recorded rectangle the recording does not include it. `OnSameCanvas` and
  `OnComponent` popups stay inside the panel / window bounds and are recorded as long
  as the panel itself is.

## Window-targeted capture

`ScreenCaptureKitRecorder` (macOS) and `FfmpegWindowRecorder` (Windows, `gdigrab title=`)
target a specific OS window rather than a screen rectangle, which removes the
region-capture failure modes described above:

- **Window movement is followed automatically.** Drag the window across the screen
  while recording and the file keeps capturing its pixels — no need to stop and
  restart.
- **Occlusion doesn't matter.** Other windows passing in front of the target window
  do not appear in the recording. The capture reads the target's own backing store,
  not the composited screen.
- **Off-screen target keeps recording.** A window dragged off the visible desktop
  still has its pixels composed, so capture continues. (Compare the region path,
  which would record whatever's now under the original rectangle.)
- **`OnWindow` popups are not captured by the target's recorder**, because the popup
  is a separate OS window with its own title — it isn't the window being targeted.
  `OnSameCanvas` and `OnComponent` popups are part of the target window's surface
  and are recorded normally.

`WaylandPortalRecorder` is structurally a third path: the portal hands the capture a
PipeWire stream from the compositor, scoped to a user-picked monitor, which then gets
cropped to the requested region. The monitor-level source means it doesn't follow a
specific window the way SCK and `FfmpegWindowRecorder` do — moving the target window
within the same monitor stays within the captured stream, but moving it off-monitor
or to another display does not. Treat its movement-and-popup behaviour as closer to
region capture than to window-targeted, with the bonus that the source is the
compositor and not raw framebuffer reads.

## HiDPI/Retina

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
  JVM exit without stop leaves a partial/non-finalised file and an orphaned subprocess.

## Current non-limitations

- Cursor capture is configurable via `RecordingOptions.captureCursor` and works under the region
  path. The cursor pixels are baked into the frames; there is no overlay you can toggle in
  post-processing.
- Audio capture is intentionally absent — Spectre currently records video only.

## Window-targeted recording

- Window-targeted capture via `ScreenCaptureKitRecorder` is the recommended path for
  top-level `ComposeWindow` surfaces on macOS. It removes the "anything overlapping the
  region appears in the video" class of problems and follows the window across the
  screen. Windows has the equivalent path via `FfmpegWindowRecorder` (`gdigrab title=`).
- The embedded-panel path is still region capture only — there's no host window for SCK
  or `gdigrab title=` to target.

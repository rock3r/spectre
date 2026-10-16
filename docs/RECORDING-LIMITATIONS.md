# Recording limitations

## Two recording modes

The `recording` module supports region and window capture. Their limits depend on
the pixel source:

- **Region capture** records a fixed desktop `Rectangle`. It includes every visible pixel
  inside that rectangle, regardless of which window owns it. macOS uses ScreenCaptureKit;
  Windows uses Windows Graphics Capture; Linux Xorg/Xvfb uses GStreamer `ximagesrc`;
  Wayland uses a compositor PipeWire stream. Use a region when no suitable top-level
  OS window is available, such as a `ComposePanel` in an IntelliJ tool window.
- **Window-targeted capture** records a selected OS window. ScreenCaptureKit and Windows
  Graphics Capture read its window surface, even when another window covers it.
  They follow window movement. Wayland captures a window stream with a crop fixed at
  `start()`, so keep its position and size unchanged. Linux Xorg/Xvfb reads visible
  framebuffer pixels, so the window must remain visible and unobscured.

Backend → mode mapping:

| Backend                       | Mode                                                |
| ----------------------------- | --------------------------------------------------- |
| `FfmpegRecorder`              | Deprecated legacy explicit region capture (`avfoundation`/`gdigrab`/`x11grab`).|
| `FfmpegRegionScreenshotter`   | Deprecated legacy explicit Linux X11 still-image region fallback (`x11grab`).  |
| `LinuxX11Recorder`            | Linux Xorg/Xvfb region and named-window capture via GStreamer `ximagesrc`. |
| `LinuxNativeScreenshotter`    | Linux Xorg/Xvfb screenshots via `ximagesrc`, and Linux Wayland screenshots via portal/PipeWire one-frame capture. |
| `WaylandPortalRecorder`       | Region capture, sourced from the Wayland portal (`SourceType.MONITOR`). |
| `WaylandPortalWindowRecorder` | Window-targeted (Linux Wayland, portal `SourceType.WINDOW` — only the picked window's pixels are captured). |
| `ScreenCaptureKitRecorder`    | Region and window-targeted capture (macOS).         |
| `ScreenCaptureKitScreenshotter` | Window-targeted still screenshots (macOS).         |
| `WindowsGraphicsCaptureRecorder` | Window-targeted and region capture (Windows Graphics Capture). |
| `FfmpegWindowRecorder`        | Deprecated legacy window-targeted (Windows, `gdigrab title=`). |
| `WindowsWindowScreenshotter`  | Window-targeted still screenshots (Windows Graphics Capture helper). |
| `AutoRecorder`                | Routes to the right one based on `TitledWindow?`.   |
| `AutoScreenshotter`           | Routes still screenshots by platform and window.    |

The following sections explain each platform's limits. See
[Window-targeted capture](#window-targeted-capture) for movement and occlusion behaviour.


## Wayland restore_token (agent non-interactive runs)

CI and hermetic tests can redirect token storage with:

- `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR` — directory for per-source token files (mode `0700`).
  Monitor+input grants are `wayland-rd-restore-token-rd-monitor-{cursor}`. Window-only
  ScreenCast grants remain `wayland-screencast-restore-token-{key}` (e.g. `…-window-embedded`).
- `SPECTRE_WAYLAND_RESTORE_TOKEN_PATH` — **base path** for separate token files. Spectre
  writes `{basename}-{key}` next to that path (mode `0600`), so keys stay separate.
  Prefer `DIR` when only the directory needs relocation.
- `SPECTRE_WAYLAND_SESSION_DIR` — directory for the long-lived helper lock and unix socket
  (`wayland-session.lock` / `wayland-session.sock`). Defaults to `$XDG_RUNTIME_DIR/spectre`.
- `SPECTRE_WAYLAND_DESKTOP_DIR` — override for the installed
  `dev.sebastiano.spectre.desktop` (defaults to `$XDG_DATA_HOME/applications`).

Headless runners without a seated GNOME session do not expose
`org.freedesktop.portal.RemoteDesktop` or `ScreenCast`.
Unit tests check token persistence, reuse, and removal without a live portal.
First consent still needs a real compositor seat and a person to accept the dialog.

On Linux Wayland, a person must accept the portal consent dialog.
`spectre-wayland-helper --session` installs `dev.sebastiano.spectre.desktop` and calls
`org.freedesktop.host.portal.Registry.Register` **before** `SelectDevices` and
`SelectSources`. It opens **one** RemoteDesktop session for keyboard, pointer, and
monitor access with `persist_mode=2`. Spectre stores replacement `restore_token` files under
`$XDG_STATE_HOME/spectre/` with file mode `0600` and directory mode `0700`.
Use `SPECTRE_WAYLAND_RESTORE_TOKEN_PATH` or `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR` to change the
location. Spectre does not store these tokens under `~/.java/robot/`.

On Wayland, in-process `RobotDriver()`, attach, CLI, and MCP use that helper for input
and monitor capture. The helper holds one session for the user seat.
Parallel JVMs connect to its socket, sharing one Robot portal session.

- **First run:** If no token exists or the compositor rejects it, the portal shows a
  Share + Remember / Allow remote interaction dialog. A person must accept it.
  Release smoke accepts it during `portal-token-warmup` and sets
  `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR` and `SPECTRE_WAYLAND_HELPER`. It runs tests
  with heavy Robot use under `xvfb-run`, even on a seated Wayland display.
  Window-source tokens apply to the selected window. Warming one against another
  window does not grant access.
- **Cursor**: the held RemoteDesktop session is opened with the cursor embedded in the
  stream. `RecordingOptions.captureCursor = false` and screenshot `HIDDEN` requests do not
  reopen a second session; monitor frames may include the pointer.
- **Keys**: helper keyboard mapping covers US letters, digits, modifiers, arrows, Enter,
  Tab, Escape, Delete, Space, and common punctuation. Other AWT `VK_*` codes fail closed.
- **Recovery**: if `$XDG_RUNTIME_DIR/spectre/wayland-session.sock` is stale after a helper
  crash, Spectre unlinks it and starts a new helper. Manual socket deletion is also possible.
- **Coordinates**: pointer motion is translated by the granted stream origin. Fractional
  HiDPI and a monitor other than the restored one can still miss; pick the same screen at
  first consent.
- **Later CLI and agent sessions:** The helper restores the RemoteDesktop grant
  without another dialog. This behaviour was validated on GNOME/mutter; other
  compositors remain best-effort. Portal tokens are single-use. Each successful
  `Start` writes a replacement file. A cancelled dialog stores no token.
- **Invalidation**: if SelectDevices/SelectSources/Start rejects the token, Spectre clears
  the file, retries interactively once, and fails closed if that retry fails.

X11, macOS, and Windows keep their input and capture paths.
`RobotDriver(robot)` still wraps the supplied `java.awt.Robot`.

## Platform

- **macOS** — ScreenCaptureKit region and window capture through the
  `spectre-recording-macos` helper. Requires the Screen Recording permission. `AutoRecorder`
  no longer falls back to `FfmpegRecorder`/`avfoundation` when that helper artifact is absent
  or when callers set SCK-incompatible options such as a custom `RecordingOptions.codec`.
  `RecordingOptions.screenIndex` is the SCK display index: primary display first, then by
  display frame `minX` and `minY`. The explicit `FfmpegRecorder` remains available only as a
  deprecated legacy backend.
- **Windows** — `spectre-recording-windows` supplies a shared .NET helper for window,
  region, and fullscreen recordings and still window screenshots. It uses Windows
  Graphics Capture and supports x64 and arm64. It does not require ffmpeg. Install
  .NET 8 Desktop Runtime and Windows App Runtime 1.8 for this framework-dependent
  helper. `AutoRecorder.startRegion(...)` fails if the artifact is absent. It also
  fails when `RecordingOptions.codec` or `screenIndex` requests an unsupported
  Windows region option. The deprecated `FfmpegRecorder` remains available for
  explicit legacy use.
- **Linux Xorg/Xvfb sessions** — The helper reads `DISPLAY` and captures through
  GStreamer `ximagesrc`. `LinuxX11Recorder` records fixed regions or a named X11
  window. `LinuxNativeScreenshotter` writes one-frame PNGs through the helper.
  Named-window capture resolves the XID from an exact `WM_NAME` or `_NET_WM_NAME`,
  including characters such as `·`. It then runs `ximagesrc xid=`. An unmatched
  mapped window title makes `start(...)` fail; GStreamer's `xname=` path could
  otherwise record the desktop. Routine validation covers one Ubuntu 22.04 Xorg
  machine and CI under `xvfb-run` without a GPU. Other Xorg window managers and
  distributions have best-effort support, as the README states. Install
  `spectre-recording-linux` and GStreamer for `AutoRecorder` and
  `AutoScreenshotter`. The helper accepts `RecordingOptions.codec = "libx264"` or
  `"x264enc"`. Other codec strings fail at `start(...)` with a targeted error.
  The explicit ffmpeg classes remain deprecated compatibility options.

  **Xvfb validation covers Linux X11 capture only.** CI and release smoke under
  `xvfb-run` use the `ximagesrc` helper path. Validate portal consent, PipeWire capture,
  token reuse, and compositor behaviour on a real Wayland desktop.

  Session detection (#397) keeps a pure-X11 `DISPLAY`, such as Xvfb, in X11 mode.
  Residual `wayland-*` sockets under `XDG_RUNTIME_DIR` or inherited
  `WAYLAND_DISPLAY` and `XDG_SESSION_TYPE=wayland` do not override it.
  Spectre checks for an Xvfb process or runs `xdpyinfo` without the `XWAYLAND`
  extension. With no `DISPLAY`, a residual compositor socket indicates Wayland,
  including SSH sessions into a Wayland host.
  Real Wayland+XWayland still routes to the portal. Override with
  `SPECTRE_CAPTURE_BACKEND=x11` or `wayland` when probes cannot decide.

  Native Wayland sessions with XWayland are different: Mutter does not expose the composited
  desktop through the XWayland root framebuffer, so root-region `ximagesrc` capture can be black
  except for the cursor. `AutoRecorder` detects Wayland before this backend and routes region
  capture through the portal; do not force the X11 backend in that environment
  (`SPECTRE_CAPTURE_BACKEND=x11` is an escape hatch for nested Xvfb only).
- **Linux Wayland sessions** use `xdg-desktop-portal` ScreenCast and `gst-launch-1.0`.
  The Rust `spectre-wayland-helper` in `spectre-recording-linux` passes the PipeWire
  file descriptor to the encoder. Its source is in `recording/native/linux/`.
  `WaylandPortalRecorder` selects a monitor through `SourceType.MONITOR` and crops to
  the requested rectangle. `WaylandPortalWindowRecorder` selects one window through
  `SourceType.WINDOW` and crops the isolated stream to window size.
  `LinuxNativeScreenshotter` uses the same helper for a one-frame PNG.
  The JVM exchanges newline-delimited JSON with the helper over standard streams.
  **The first call opens the compositor's screen-sharing dialog.** Later calls reuse
  the portal grant through `restore_token`.
  The helper exists because dbus-java could not decode the Unix file descriptor.
  Rust can pass it to `gst-launch-1.0`; JVM `ProcessBuilder` does not expose the
  required `fcntl(F_SETFD, ...)` control. macOS also uses a separate capture helper.

  **Window-targeted Wayland capture needs `xprop` on `PATH` and GTK/Mutter frame extents.**
  Mutter includes invisible shadows in window streams, often 25 px per side for
  GNOME GTK client-side decoration. `Frame.getBounds()` covers only the inner window.
  `WaylandPortalWindowRecorder` queries `_GTK_FRAME_EXTENTS(CARDINAL)` through `xprop`
  on the JFrame's XWayland window. It uses that value to place the stream-relative crop.
  Without the extents, the crop can clip the title bar close button.
  Debian and Ubuntu provide `xprop` in `x11-utils`. Minimal images may need that package.
  The recorder throws `IllegalStateException` when `xprop` is missing, the title is blank,
  or the window manager does not publish `_GTK_FRAME_EXTENTS`.
  Older Mutter, non-GTK decoration, and KDE Plasma may lack the property.
  Use `AutoRecorder.startRegion(...)` or `WaylandPortalRecorder` for explicit region capture.
  Installing `xprop` cannot make Qt, JavaFX, Electron, SDL, KDE, or wlroots windows
  publish the GTK property.

  **HiDPI / mixed AWT and portal coordinates.** On fractional-scale Wayland, AWT/XWayland
  often reports a larger virtual desktop than the ScreenCast stream. For example,
  AWT may report 2560×1440 while the portal supplies 1536×864. Region capture
  sends the AWT display bounds that contain the requested rectangle. When their
  aspect ratios match, Spectre converts the rectangle into stream pixels and
  clamps overflow. A region outside the granted stream still fails.
  Window-source crops already use stream coordinates and need only clamping.

  Wayland still screenshots use the same portal grant model as video. They are supported
  only when the compositor's ScreenCast portal can provide the requested monitor or window
  stream and the user accepts the dialog. In unattended SSH/headless runs, a hidden or
  unanswered portal dialog surfaces as a helper timeout. It never returns a silent blank PNG.

  The smoke runner validated real-pixel MP4 capture on Ubuntu 22.04/GNOME 42,
  Ubuntu 24.04/GNOME 46, and Ubuntu 26.04/GNOME 50 with Mutter.
  Runs on 2026-05-02 and 2026-05-03 checked the 24.04 and 26.04 paths.
  The 24.04 run confirmed that `cursor_mode=Embedded` includes the system pointer when
  `RecordingOptions.captureCursor=true`. The 26.04 run confirmed window-sized output,
  visible window decorations, and isolation from other apps.
  Routine validation does not cover KDE Plasma, sway, wlroots, other distributions,
  or other Ubuntu versions. The portal API is standard across compositors;
  `_GTK_FRAME_EXTENTS` is specific to GNOME/Mutter.
  On KDE or sway, window capture may fail with "Could not determine WM frame extents".
  Use `WaylandPortalRecorder` for region capture until a compositor-specific lookup exists.

  **The compositor controls source frame rate.** `videorate` targets
  `RecordingOptions.frameRate`, which defaults to 30.
  It repeats the last frame when the source is slower and drops extra frames when
  the source is faster. Physical GPU-backed desktops in validation sustained 30 fps.
  On Hyper-V or VirtualBox software GPUs, the source delivered about 5–25 fps.
  The MP4 can therefore report 30 fps while visibly repeating frames.
  Those repetitions reflect missing source frames.
  Lower `RecordingOptions.frameRate` to the VM's observed rate if stutter matters.
  A target of 15 fps often works for software-rendered VM GPUs.

## Region capture

- **Region capture records a fixed screen rectangle.** `Recorder.start(...)` sets the
  `Rectangle`; it does not follow window movement. The recording includes whatever
  appears there. For a top-level window, use `ScreenCaptureKitRecorder` or
  `WindowsGraphicsCaptureRecorder`. For an embedded surface with a suitable host window,
  use window capture with a crop.
- **Embedded `ComposePanel` surfaces:** If a top-level `Frame` or `JFrame` hosts the
  panel, call `AutoRecorder.startWindow(window, output, cropInWindow = surfaceBoundsInWindow,
  scaleX = …, scaleY = …)`. Spectre captures the host window and crops to the
  panel. macOS uses `sourceRect`; Windows crops before encoding. Other apps do
  not obscure the captured host window, and capture follows its movement.
  **The v1 crop stays fixed from start.** Moving or resizing the panel can
  misalign frames. Stop and restart to update the crop. Spectre prints a
  stderr warning when a crop is used. Linux X11/Wayland do not implement
  window+crop yet. Use explicit `startRegion(...)` when no suitable host window
  or supported crop backend exists. Region capture has these limits:
  - Anything that visually overlaps the panel appears in the recording, including other windows,
    the menu bar, OS notifications, and floating popups that escape the panel's bounds.
  - The captured region is the panel's screen-space bounds at start. If the host window moves or
    resizes while recording, the panel's pixels drift out of the captured rectangle. The recording
    then contains whatever is under the original rectangle (often empty desktop).
  - Off-screen panels record black frames.

### Window movement and popups under region capture

These movement and popup limits apply to region capture.

- **Window movement isn't followed.** Move the host window after `startRegion(...)` and the
  recording keeps capturing the original screen rectangle. Stop and restart to follow
  the new position.
- **Popups outside the region are clipped.** Compose Desktop's `OnWindow` layer
  uses a separate top-level OS window. The recording excludes its pixels outside
  the selected rectangle. `OnSameCanvas` and `OnComponent` popups remain inside
  panel or window bounds and appear while the panel does.

## Window-targeted capture

`ScreenCaptureKitRecorder` on macOS and `WindowsGraphicsCaptureRecorder` on Windows
capture a selected OS window. They follow these rules:

- **Window movement is followed automatically.** Drag the window across the screen
  while recording and the file keeps capturing its pixels. There is no need to stop and
  restart.
- **Occlusion doesn't matter.** Other windows passing in front of the target window
  do not appear in the recording. The capture reads the target's own backing store,
  not the composited screen.
- **Off-screen target keeps recording.** A window dragged off the visible desktop
  still has its pixels composed, so capture continues. (Compare the region path,
  which would record whatever's now under the original rectangle.)
- **`OnWindow` popups are not captured by the target's recorder**, because the popup
  is a separate OS window with its own title. The recorder targets the original window.
  `OnSameCanvas` and `OnComponent` popups are part of the target window's surface
  and are recorded normally.

`WaylandPortalRecorder` receives a PipeWire stream for a monitor selected by the user.
It crops that stream to the requested region. The source follows the monitor,
independent of any individual window. A window can move within the monitor and remain in the
stream. Moving it to another display removes it from the source.
Popups and movement follow the monitor-region limits described above.

## HiDPI/Retina

- The recorded resolution uses **screen pixels**. A 400×300dp
  region on a 2× display becomes an 800×600 pixel video.
- Still capture follows the same rule, so a `spectre capture` PNG and a recording of the same
  window come out at the same resolution. See
  [Atomic capture — Pixel scale](guide/capture.md#pixel-scale).
- `Rectangle` coordinates passed to `start(...)` are in screen pixels. Prefer
  `AutomatorNode.boundsOnScreen`; it already divides by the display scale and adds the Compose
  surface's screen origin. `boundsInWindow` is already in Compose / device pixels (density-scaled)
  and window-relative; manual conversion requires division by the display scale **and** addition
  of the surface/panel screen origin. Dividing alone lands near `(0, 0)` when the window is not at the
  screen origin. Do not multiply by density.

## Permissions and process lifecycle

- The JVM process needs **macOS Screen Recording permission** (`MacOsRecordingPermissions`
  documents the path). Without it, the SCK helper exits during startup and `Recorder.start`
  surfaces an error before returning a handle.
- The macOS console session must be **unlocked**. A locked screen can make Robot
  screenshots or ScreenCaptureKit recordings fail, or produce black/incorrect frames, even
  when Screen Recording TCC is granted. Unlock the display and retry before treating the
  failure as a missing permission.
- `start(...)` launches the encoder or helper before returning.
  Frames then begin reaching the output file. Missing binaries, unsupported codecs,
  permissions, or busy devices make `start(...)` throw.
- Call `RecordingHandle.stop(...)` to flush and finalise the file.
  Exiting the JVM without stop can leave a partial file and an orphaned process.

## JUnit failure-video policy (#206)

`ComposeAutomatorExtension` / `ComposeAutomatorRule` can record the whole test and keep or
delete the file at the end (`FailureVideoPolicy`: `Off` | `OnFailureKeep` | `Always`). See
[JUnit integration — Failure video](guide/junit.md#failure-video).

Recording overhead:

- **Per-OS cost** — the same backends as `AutoRecorder` run for the full test duration
  (ScreenCaptureKit helper on macOS, Windows Graphics Capture helper on Windows, GStreamer
  X11 / Wayland portal on Linux). CPU, encoder, and helper-process cost scale with test
  length and resolution; capability and permission limits on this page still apply.
- **Disk under `OnFailureKeep`** — the video file is written during **every** invocation
  (including green tests) and only deleted after stop+finalize on pass. Parallel suites and
  long-running tests can therefore spike disk use during the run even when nothing remains
  after a green suite.
- **Stop before delete** — Spectre always stops the recorder and finalizes the file before
  applying keep/delete. A JVM exit without teardown can still leave a partial file and an
  orphaned helper (same as any other `RecordingHandle`; see process lifecycle above).
- **In-process JUnit only** — this policy does not cover agent/attach / CLI `spectre record`
  paths; those remain separate entry points.

## Other recording behaviour

- Cursor capture is configurable via `RecordingOptions.captureCursor` and works under the region
  path. The cursor pixels are baked into the frames; there is no overlay to toggle in
  post-processing.
- Audio capture is intentionally absent — Spectre currently records video only.

## Window-targeted recording

- Window-targeted capture via `ScreenCaptureKitRecorder` is the recommended path for
  top-level `ComposeWindow` surfaces on macOS. It removes the "anything overlapping the
  region appears in the video" class of problems and follows the window across the
  screen. Windows has the equivalent path via `WindowsGraphicsCaptureRecorder`.
- An embedded panel can use host-window capture with a fixed crop on macOS and
  Windows when its `Frame` or `JFrame` has a usable handle. On Linux, or without
  a suitable host window, use explicit region capture.

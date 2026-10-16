# Recording and screenshots

The `recording` module captures video and native window screenshots for tests.
Platform backends provide pixels. `AutoRecorder` and `AutoScreenshotter` select a backend
for each call.

!!! note "External dependencies"
    Recording uses small native helpers on macOS, Linux, and Windows.
    Deprecated explicit legacy backends use `ffmpeg`. macOS window and region
    recording use the ScreenCaptureKit helper in `spectre-recording-macos`.
    Linux Xorg/Xvfb and Wayland use `spectre-recording-linux`; GStreamer encodes
    video or writes PNGs. Windows window, region, and fullscreen recording use
    the Windows Graphics Capture helper in `spectre-recording-windows`.
    Add `spectre-recording-macos`, `spectre-recording-linux`, and/or
    `spectre-recording-windows` as runtime-only dependencies for those helpers. See
    [Recording limitations](../RECORDING-LIMITATIONS.md) for the per-platform notes.

!!! note "Linux migration note"
    Linux Xorg/Xvfb recording and screenshots now use the bundled
    `spectre-recording-linux` helper with GStreamer. The previous Linux ffmpeg/x11grab
    fallback is no longer used by `AutoRecorder` or `AutoScreenshotter`; `ffmpeg` on
    `PATH` is only relevant when instantiating the explicit legacy ffmpeg backends.

!!! note "Windows migration note"
    Windows window, region, and fullscreen recording use the
    `spectre-recording-windows` helper through Windows Graphics Capture.
    `AutoRecorder` fails at `start(...)` if the helper artifact is absent. It
    also fails for unsupported Windows options such as custom
    `RecordingOptions.codec` or `screenIndex`. Only explicit legacy ffmpeg
    backends need `ffmpeg` on `PATH`.

!!! note "macOS migration note"
    macOS window and region recording use the `spectre-recording-macos`
    ScreenCaptureKit helper. `AutoRecorder` fails at `start(...)` if that artifact
    is absent or an option such as custom `RecordingOptions.codec` conflicts
    with SCK. Only explicit legacy ffmpeg backends need `ffmpeg` on `PATH`.

!!! note "macOS TCC preflight + guided grant"
    Capture and recording **never** open the Screen Recording prompt or guide UI
    implicitly. They preflight with `CGPreflightScreenCaptureAccess` and fail fast with a
    structured error when access is missing. Agents cannot click TCC prompts.
    With a person present, run `spectre permissions check` or
    `spectre permissions request`. The **request** command opens the Spectre
    Capture Helper guide when access is missing. It explains the permission,
    opens Settings, shows icon placement, and checks the grant again.

## Still window screenshots

With `spectre-recording` at runtime, `ComposeAutomator.screenshot(windowIndex)` and
`screenshot(node)` use native window capture. Core does not depend on recording.
Injected or core-only deployments report an error for these overloads when native
capture is unavailable. They do not substitute a framebuffer region.
Use `screenshot(region)` for an explicit screen rectangle.
For a top-level AWT window, call `AutoScreenshotter` directly:

```kotlin
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import javax.imageio.ImageIO
import java.io.File

val image = AutoScreenshotter().captureWindow(composeWindow.asTitledWindow())
ImageIO.write(image, "png", File("build/reports/window.png"))
```

`AutoScreenshotter.captureWindow(...)` returns a `BufferedImage`.

| Platform | Backend | Occlusion behavior | Notes |
| --- | --- | --- | --- |
| macOS | ScreenCaptureKit helper (`spectre-screencapture --mode screenshot`) | Captures only the window source; overlapping apps are excluded | Requires `spectre-recording-macos` at runtime and Screen Recording permission |
| Windows | Windows Graphics Capture helper (`spectre-window-capture.exe`) | Captures the named top-level window | Requires `spectre-recording-windows`, Windows 10 version 1903 or newer, .NET 8 Desktop Runtime, Windows App Runtime 1.8, and a non-blank exact window title |
| Linux Xorg/Xvfb | Linux helper + GStreamer `ximagesrc` | Captures visible screen pixels | The target window must be visible/frontmost, same limitation as `ComposeAutomator.screenshot(...)`; window mode needs a non-blank exact title |
| Linux Wayland | Linux helper + portal/PipeWire one-frame capture | Portal-scoped; region mode captures the selected monitor stream, window mode captures the selected window stream | Requires the compositor portal dialog to be accepted. Window mode needs `xprop` and `_GTK_FRAME_EXTENTS`, same as `WaylandPortalWindowRecorder` |

An embedded `ComposePanel` needs its **host** top-level window for window capture.
When the host exposes a titled `Frame`, capture that window and crop to the panel
if needed.

### Still Screenshot Smoke Tests

Run the cross-platform smoke first:

```bash
./gradlew :recording:runWindowScreenshotSmoke
```

Expected results by platform:

- **macOS** — a Swing window opens, `ScreenCaptureKitScreenshotter` captures it through
  `spectre-screencapture --mode screenshot`, and the task prints a PNG path such as
  `$TMPDIR/spectre-window-screenshot-smoke.png`. Open the PNG and confirm the white window
  with `screenshot smoke` text is visible. If macOS denies Screen Recording, grant the
  host JVM permission in System Settings, restart the JVM/terminal, and rerun. If the
  screen is locked, macOS can block or black out capture even when TCC is granted; unlock
  the display and rerun before chasing permissions.
- **Windows** — the same task uses the `spectre-window-capture.exe` helper from
  `spectre-recording-windows`. The smoke should print a non-empty PNG path. Open it and
  confirm it contains only the smoke window. Still screenshots do not require ffmpeg.
  Runtime users need .NET 8 Desktop Runtime and Windows App Runtime 1.8 installed.
- **Linux Xorg/Xvfb** — run from a real Xorg/Xvfb display with `DISPLAY` set and
  `spectre-recording-linux` on the runtime classpath. The task uses the Linux helper's
  GStreamer `ximagesrc` path, so keep the smoke window visible/frontmost. Open the printed
  PNG and confirm it contains the smoke window. Do not force this smoke through XWayland
  inside a native Wayland compositor: Mutter's XWayland root framebuffer is black even though
  the pointer is visible. Normal Wayland sessions should validate the portal route below.
  **`xvfb-run` proves X11 only.** Portal/PipeWire requires separate validation. Nested Xvfb on a host that still
  exports Wayland env or `wayland-*` sockets is handled by session detection (#397); set
  `SPECTRE_CAPTURE_BACKEND=x11` for an explicit override.
- **Linux Wayland** — run with `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, and the D-Bus session
  bus visible to the JVM. The task asks the compositor portal for a window stream and writes
  a one-frame PNG after portal consent. If the dialog is hidden or rejected, the helper
  fails loudly with the portal error or timeout. To test window-targeted Wayland video, run:

  ```bash
  ./gradlew :recording:runWaylandPortalWindowSmoke
  ```

  Pick the smoke window in the compositor portal dialog and inspect the resulting video.

## Linux Wayland consent

On a seated GNOME/Mutter desktop, Spectre uses **one** long-lived `spectre-wayland-helper`
session for monitor capture and real OS input:

1. The helper installs `dev.sebastiano.spectre.desktop` and calls the host portal
   `Registry.Register` before any ScreenCast or RemoteDesktop method, so the grant is bound
   to Spectre. An empty host `app_id` would lose that identity.
2. It opens one RemoteDesktop session (keyboard + pointer + monitor source, persist until
   revoked). The first run shows Share + Remember / Allow remote interaction. Later Spectre
   processes on that seat connect to `$XDG_RUNTIME_DIR/spectre/wayland-session.sock`
   through the existing portal session.
3. Replacement `restore_token` values live under `$XDG_STATE_HOME/spectre/` (or
   `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR`). They are not stored in `~/.java/robot/`.
4. If the compositor rejects a stored token, Spectre clears it, retries the dialog once,
   and fails closed if that retry fails.

`RobotDriver()`, attach (including the inject payload, which talks to the existing seat
socket without shipping `spectre-recording`), CLI, and MCP on Wayland use this helper. `RobotDriver(robot)` still
wraps the supplied `java.awt.Robot`. X11, macOS, and Windows keep their existing backends.
Window-targeted portal capture is a separate ScreenCast grant and is still bound to the
picked window.

See [Recording limitations](../RECORDING-LIMITATIONS.md) for token env vars and the
compositor matrix.

For the Windows WGC video path, run:

```bash
./gradlew :recording:runWindowsGraphicsCaptureRecordingSmoke
```

The smoke opens a Swing window, starts `AutoRecorder.startWindow(...)`, moves the window,
drives a real Robot click into it, stops the recording, and verifies that the MP4 is non-empty.
For explicit Windows region recording, run `:recording:runWindowsGraphicsCaptureRegionSmoke`.
`runWindowsGraphicsCaptureFullscreenSmoke` records the primary monitor and is intentionally
manual/intrusive.

## The `Recorder` interface

```kotlin
interface Recorder {
    fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle
}
```

A recording starts with a `RecordingHandle`; stop that handle when recording is
complete. Implementations spawn the underlying process eagerly and fail fast when startup
is clearly broken. Some process-backed recorders still cannot prove that the first frame
has already landed in `output` before `start()` returns.

## Recorder capability matrix

`AutoRecorder` chooses a backend from the platform and requested capture mode.
This matrix describes each recorder's source, movement behaviour, and requirements.

| Recorder | Capture shape | Follows window resize? | Captures occluding windows / popups? | Prerequisites | Platforms |
| --- | --- | --- | --- | --- | --- |
| `FfmpegRecorder` (deprecated) | Screen region (fixed `Rectangle`) | No — region fixed at `start()` | Yes — anything visible inside the region lands in the frame, including occluding apps and overlapping Compose `OnWindow` popups | `ffmpeg` on `PATH`; macOS Screen Recording TCC for the launching app | macOS · Windows legacy/explicit · Linux X11 legacy/explicit |
| `WindowsGraphicsCaptureRecorder` | Named window (exact title + owner pid), optional pre-encode crop, or screen region | Window mode follows the target window across moves; optional crop is **fixed at start** (v1). Region mode is fixed at `start()` | Window mode (with or without crop) captures only the target window's pixels — occluders excluded. Region/fullscreen mode captures visible monitor pixels including overlapping windows and popups | `spectre-recording-windows` runtime helper (`spectre-window-capture.exe`); Windows 10 version 1903 or newer; .NET 8 Desktop Runtime; Windows App Runtime 1.8 | Windows |
| `FfmpegWindowRecorder` (deprecated) | Legacy named window (`gdigrab title=`) | Yes — `gdigrab` retracks the window across moves and resizes | Captures only the window's pixels. Compose `OnWindow` popups land in a separate OS window with a different title and are excluded. `OnSameCanvas` / `OnComponent` popups are part of the window surface and are captured | `ffmpeg` on `PATH`; visible window with a non-blank exact title | Windows |
| `ScreenCaptureKitRecorder` | Named window (pid + title substring), optional window-relative crop, or screen region | Window mode follows moves/resizes of the host window; optional `sourceRect` crop is **fixed at start** (v1). Region mode is fixed at `start()` | Window mode (with or without crop) captures only the window's pixels — occluders excluded. Region mode captures visible display pixels including overlapping apps | `spectre-recording-macos` runtime helper (`spectre-screencapture`); macOS Screen Recording TCC for the launching app | macOS |
| `LinuxX11Recorder` | Screen region or named X11 window (`ximagesrc`) | Region mode is fixed at `start()`; window mode resolves the XID from `WM_NAME` / `_NET_WM_NAME` and captures with `ximagesrc xid=` | Captures visible X server pixels for the selected source; occluding apps can appear in region capture. A title that does not match a mapped window fails; root/desktop recording is excluded | `spectre-recording-linux` runtime helper; `gst-launch-1.0` with `ximagesrc`, `videorate`, `x264enc`, and `mp4mux`; `DISPLAY`; `libX11.so.6` at runtime for named-window lookup | Linux Xorg/Xvfb; named XWayland windows only when the process is deliberately forced onto the X11 backend |
| `WaylandPortalRecorder` | Monitor region (portal `SourceType.MONITOR`, cropped) | No — monitor-level source | Depends on the compositor. GNOME/Mutter validation includes the user's overlays and excludes apps occluding the recorded region | `spectre-recording-linux` runtime helper (`spectre-wayland-helper`); `xdg-desktop-portal` + PipeWire; `gst-launch-1.0` on `PATH`. First human consent stores a `restore_token` under `$XDG_STATE_HOME/spectre/` so later agent sessions skip the dialog (#188) | Linux Wayland |
| `WaylandPortalWindowRecorder` | Named window (portal `SourceType.WINDOW` + fixed crop) | **No.** The crop is computed once from `_GTK_FRAME_EXTENTS` at `start()` and stays at those pixel coordinates for the rest of the recording. Moving or resizing the window during recording misaligns the crop (typically the recording shows blank / black for the unrendered area of the original rectangle); see `WaylandPortalWindowRecorder`'s KDoc for the detailed rationale | The stream contains only the picked window's pixels; `OnWindow` popups are excluded | `spectre-recording-linux` runtime helper; `xdg-desktop-portal` + PipeWire; `gst-launch-1.0` on `PATH`; `xprop` on `PATH`; compositor must publish `_GTK_FRAME_EXTENTS` (GNOME/Mutter verified; older Mutter / KDE / sway may fail with an error to avoid misaligned video) | Linux Wayland |

Wayland window capture receives only the selected window's pixels.
Other applications cannot occlude that source. Its crop stays fixed from `start()`,
so moving or resizing the window can misalign the recording.

For a `ComposePanel` inside a host window, use **window capture with a crop**.
This includes Jewel tool windows in IntelliJ. Pass the host as a `TitledWindow`.
Supply panel bounds relative to the host in AWT user space through
`AutoRecorder.startWindow(..., cropInWindow = ..., scaleX = ..., scaleY = ...)`.
These bounds match window identity `surfaceBoundsInWindow`.
macOS uses `SCStreamConfiguration.sourceRect`; Windows crops before encoding in WGC.
The v1 crop stays **fixed from start**, so moving or resizing the panel can misalign frames.
Spectre prints a stderr warning when using a crop.
Linux X11 and Wayland do not support window crop and report an error.
Use `startRegion(...)` explicitly if a fixed, occlusion-sensitive rectangle is acceptable.

## `AutoRecorder` — pick a backend per call

`AutoRecorder` selects a backend for each call from its platform and arguments:

```kotlin
import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Rectangle
import java.nio.file.Path

val recorder = AutoRecorder()
// Full window (occlusion-immune):
val handle = recorder.startWindow(
    window = composeWindow.asTitledWindow(), // any java.awt.Frame works
    output = Path.of("build/recordings/my-test.mp4"),
    options = RecordingOptions(),
)

try {
    // ...drive the UI
} finally {
    handle.stop()
}
```

`TitledWindow` is an interface. `Frame.asTitledWindow()` adapts a production AWT frame.
Tests can provide a small in-memory implementation.

Use `startRegion(...)` for an explicit screen rectangle:

```kotlin
val handle = recorder.startRegion(
    region = Rectangle(100, 100, 800, 600),
    output = Path.of("build/recordings/my-test.mp4"),
    options = RecordingOptions(),
)
```

`startWindow(...)` reports an error when a window backend is unavailable.
It does not switch to region capture. Call `startRegion(...)` explicitly when a
fixed screen rectangle is acceptable.

`AutoRecorder` selects these backends by platform and target:

| Platform                | `window`                          | Backend chosen                                                       |
| ----------------------- | --------------------------------- | -------------------------------------------------------------------- |
| **macOS**               | non-null                          | `ScreenCaptureKitRecorder` (`spectre-recording-macos` helper).       |
| **macOS**               | `null`                            | `ScreenCaptureKitRecorder` region capture (`spectre-recording-macos` helper). |
| **Windows**             | non-null with a non-blank title   | `WindowsGraphicsCaptureRecorder` (`spectre-recording-windows` helper). |
| **Windows**             | `null`, or non-null with no title | `WindowsGraphicsCaptureRecorder` region capture.                     |
| **Linux Xorg/Xvfb**     | non-null with a non-blank title   | `LinuxX11Recorder` window capture (`ximagesrc xid=` after title lookup). |
| **Linux Xorg/Xvfb**     | `null`, or non-null with no title | `LinuxX11Recorder` region capture (`ximagesrc`).                     |
| **Linux Wayland**       | non-null                          | `WaylandPortalWindowRecorder` (portal `Window` source type — only the picked window's pixels, window-sized output). |
| **Linux Wayland**       | `null`                            | `WaylandPortalRecorder` (portal `Monitor` source type — region capture). |

Each platform has additional limits:

- **macOS requires the SCK helper.** Without its Swift helper at runtime, window and
  region calls fail. `AutoRecorder` does not use `FfmpegRecorder` or `avfoundation` as
  a fallback. Permission errors, missing windows, and helper crashes propagate.
  For region capture, `RecordingOptions.screenIndex` selects the primary display first.
  Other displays follow frame `minX` and `minY` order. The helper writes H.264 through
  `AVAssetWriter` and rejects custom codec strings.
- **Windows requires the WGC helper.** `AutoRecorder.startRegion(...)` fails when
  `spectre-recording-windows` is missing. It does not switch to `FfmpegRecorder` or
  `gdigrab`. WGC rejects custom `RecordingOptions.codec` and `screenIndex`.
  Window, fullscreen, and still capture also need the helper.
  WGC runtime failures propagate to the caller.
- **Linux Xorg/Xvfb uses the Linux helper.** `AutoRecorder` sends region and window
  capture through the `spectre-recording-linux` Rust helper. It starts `gst-launch-1.0`
  with `ximagesrc`. Window mode resolves a mapped XID from `WM_NAME` or `_NET_WM_NAME`.
  A missing title causes an error; it does not select the root desktop.
  `FfmpegRecorder`, `FfmpegWindowRecorder`, `FfmpegRegionScreenshotter`, and
  `FfmpegWindowScreenshotter` remain deprecated explicit legacy options.
  The helper accepts `RecordingOptions.codec = "libx264"` or `"x264enc"`.
  It rejects other encoder strings until the pipeline has structured configuration.
- **Linux Wayland uses the portal.** Native Wayland capture cannot use the X11 route.
  With `window != null`, `WaylandPortalWindowRecorder` requests `SourceType.WINDOW`.
  The user selects a window, and the helper crops its isolated PipeWire stream to
  window size. With `window == null`, `WaylandPortalRecorder` requests
  `SourceType.MONITOR` and crops the selected monitor stream to the requested rectangle.
  This supports embedded `ComposePanel` region capture. The first call opens the
  compositor's sharing dialog. Later calls in the same JVM reuse the grant.
- **Linux helper.** Both portal recorders run the same small Rust binary
  (`spectre-wayland-helper`) from the `spectre-recording-linux` runtime artifact. The
  same binary also owns Linux Xorg/Xvfb capture. On Wayland it drives
  `xdg-desktop-portal`'s ScreenCast interface and hands a PipeWire FD to
  `gst-launch-1.0`; on Xorg/Xvfb it runs GStreamer's `ximagesrc` directly.
- **Window-targeted Wayland needs `xprop`.** `WaylandPortalWindowRecorder` runs
  `xprop` on the JFrame's XWayland window to read `_GTK_FRAME_EXTENTS`. It uses
  those extents to calculate the crop within the stream. Mutter adds about
  25 pixels of GTK shadow outside AWT's `frame.getBounds()`. Without the extents,
  the crop clips the close button. On Debian/Ubuntu, `xprop` comes from
  `x11-utils`; minimal images may need that package. The recorder throws
  `IllegalStateException` if `xprop` or `_GTK_FRAME_EXTENTS` is unavailable.
  Older Mutter, non-GTK CSD, and KDE or sway server-side decorations may lack
  the property. Installing `xprop` only resolves the missing binary. Qt,
  JavaFX, Electron, SDL, and other non-GTK windows may still lack the property.
  Use `startRegion(...)` for explicit region capture. See
  [Recording limitations](../RECORDING-LIMITATIONS.md#platform) for more.

## Direct backend selection

Instantiate a backend directly when a test needs a specific implementation:

| Backend                       | Use it for                                                                |
| ----------------------------- | ------------------------------------------------------------------------- |
| `FfmpegRecorder`              | Deprecated legacy explicit region capture on macOS and explicit Windows/Linux ffmpeg backends. (Throws on Wayland — use `WaylandPortalRecorder` there.) |
| `WindowsGraphicsCaptureRecorder` | Windows-only window-targeted and region/fullscreen capture via the `spectre-recording-windows` Windows Graphics Capture helper. |
| `FfmpegWindowRecorder`        | Deprecated legacy explicit Windows-only window-targeted capture via `gdigrab title=`. |
| `WindowsWindowScreenshotter`  | Windows-only still window screenshots via the `spectre-recording-windows` Windows Graphics Capture helper. |
| `LinuxX11Recorder`            | Linux Xorg/Xvfb region and named-window capture via the `spectre-recording-linux` helper and GStreamer `ximagesrc`. |
| `LinuxNativeScreenshotter`    | Linux Xorg/Xvfb screenshots via `ximagesrc`, and Linux Wayland screenshots via portal/PipeWire one-frame capture. |
| `ScreenCaptureKitRecorder`    | macOS-only window-targeted and region capture via the `spectre-recording-macos` Swift helper. |
| `WaylandPortalRecorder`       | Linux Wayland region capture via `xdg-desktop-portal` (`SourceType.MONITOR`) and the `spectre-recording-linux` Rust helper. |
| `WaylandPortalWindowRecorder` | Linux Wayland window-targeted capture via `xdg-desktop-portal` (`SourceType.WINDOW`). Window-sized output containing only the picked window's pixels. |

## Per-OS prerequisites

Install the helper and system dependencies for the host platform.

### macOS

- Add `dev.sebastiano.spectre:spectre-recording-macos:<version>` as a runtime-only
  dependency. Its jar carries the notarized universal ScreenCaptureKit helper as an app
  bundle at `native/macos/SpectreCaptureHelper.app/…`; the base `spectre-recording`
  artifact does not bundle this helper.
- **Screen Recording TCC permission.** In System Settings → Privacy & Security →
  Screen & System Audio Recording, grant access to **Spectre Capture Helper**.
  Its bundle ID is `dev.sebastiano.spectre.screencapture`. The terminal or JVM
  that launched Spectre does not receive this helper grant.
- **An unlocked console session.** A locked macOS screen can make screenshots or
  recordings fail, or produce black frames, even when Screen Recording TCC is already
  granted.

macOS attributes Screen Recording for the capture helper to the **helper app bundle**
when preflight/capture run the nested executable under
`SpectreCaptureHelper.app/Contents/MacOS/`. Settings should show **Spectre Capture Helper**
with the Spectre icon. Use `spectre permissions check` / `spectre permissions request`
to verify or re-trigger the grant (agents cannot click TCC prompts).

If Settings shows a grant only for Terminal, IntelliJ, or java, check for an
older helper build or an override path. Upgrade Spectre and unset
`SPECTRE_SCREENCAPTURE_HELPER` to use the bundled app. See
[Troubleshooting](troubleshooting.md#macos-recording-errors-out-or-produces-no-file)
for failure modes when permission is missing.

Spectre extracts the helper app from `spectre-recording-macos` before running it. By default
it uses a fixed per-user path (updated in place when the helper content changes):

```text
~/Library/Application Support/spectre/helpers/spectre-screencapture/SpectreCaptureHelper.app
```

Grant Screen Recording to **Spectre Capture Helper** once in System Settings. Later Spectre
versions overwrite the same app path so the stable bundle id keeps the grant (when the
helper is Developer ID signed with the same identity).

For a project-specific helper location, set the extraction directory before the test JVM
starts:

```kotlin
systemProperty(
    "spectre.recording.screencapturekit.helperDir",
    layout.buildDirectory.dir("spectre-helper").get().asFile.absolutePath,
)
```

Then grant Screen Recording to `<helperDir>/SpectreCaptureHelper.app` instead.

A non-blank `helperDir` wins over `SPECTRE_SCREENCAPTURE_HELPER`. A blank `helperDir`
ignores that environment variable and uses the default per-user directory above. The
environment variable applies only when `helperDir` is unset, and then it points at an
existing helper. In that case, the bundled app is not extracted.

`apple.awt.UIElement=true` helper/test JVMs are useful with `RobotDriver.synthetic(...)`
for focus-safe per-character `typeText`. Clipboard-backed `pasteText` and recording
still go through macOS services outside Spectre's synthetic key path. Prefer a normal
foreground-capable parent process for recording tests, especially while establishing Screen
Recording TCC grants. See [Running on CI](ci.md#macos-helper-jvms) for the CI-side
trade-offs.

### Other platforms

Spectre extracts bundled native helpers to stable per-user directories on every platform:
`~/Library/Application Support/spectre/helpers` on macOS, `%LOCALAPPDATA%\\spectre\\helpers`
on Windows, and `$XDG_CACHE_HOME/spectre/helpers` or `~/.cache/spectre/helpers` on Linux.

- **Windows** — Add `dev.sebastiano.spectre:spectre-recording-windows:<version>`
  at runtime for native window, region, or fullscreen video and still window
  screenshots. Runtime needs Windows 10 version 1903 or newer, .NET 8 Desktop
  Runtime, and Windows App Runtime 1.8. Source builds and CI need .NET 8 SDK.
  `AutoRecorder.startRegion(...)` requires this helper. Explicit legacy ffmpeg
  backends require `ffmpeg`.
- **Linux Xorg/Xvfb** — add `dev.sebastiano.spectre:spectre-recording-linux:<version>`
  as a runtime-only dependency. Runtime machines need `gst-launch-1.0` plus GStreamer
  plugins for `ximagesrc`, H.264, MP4 muxing, PNG encoding, and colour conversion.
  On Debian/Ubuntu:

  ```bash
  sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-base \
      gstreamer1.0-plugins-good gstreamer1.0-plugins-ugly x11-utils
  ```

  `x11-utils` supplies `xprop` for window-mode metadata.
- **Linux Wayland** — use the same `spectre-recording-linux` runtime dependency and
  GStreamer packages, plus `gstreamer1.0-pipewire`, `xdg-desktop-portal`, and a Wayland
  compositor that exposes `org.freedesktop.portal.ScreenCast`; tested against
  GNOME/mutter on Ubuntu 22.04 and 24.04. On Debian/Ubuntu:

  ```bash
  sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-base \
      gstreamer1.0-plugins-good gstreamer1.0-plugins-ugly \
      gstreamer1.0-pipewire xdg-desktop-portal x11-utils
  ```

See [Recording limitations](../RECORDING-LIMITATIONS.md) for frame drops, minimum
dimensions, crop limits, and audio support.

## Stopping cleanly

`RecordingHandle.stop()` waits for the encoder to flush.
Use `try`/`finally` so a test failure still calls it:

```kotlin
val handle = recorder.startWindow(/* ... */)
try {
    // test body
} finally {
    handle.stop()
}
```

Without `stop()`, the `ffmpeg`, native helper, or `gst-launch` process may keep writing.
The output file may be truncated or unfinalised.

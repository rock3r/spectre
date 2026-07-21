# Recording And Screenshots

The `recording` module adds video output and native still-window screenshots to your
tests. It exposes small surfaces backed by platform-specific implementations and
routers that pick the right one per call.

!!! note "External dependencies"
    The recording backends shell out to platform tools — small native helpers for macOS,
    Linux, and Windows Graphics Capture, plus `ffmpeg` only for deprecated explicit
    legacy backends. macOS window and region recording both use the ScreenCaptureKit helper
    from `spectre-recording-macos`. Linux Xorg/Xvfb and Wayland capture both use the Linux helper from
    `spectre-recording-linux`, with GStreamer doing the actual encoding or PNG writing.
    Windows window, region, and fullscreen recording use the Windows Graphics Capture
    helper when `spectre-recording-windows` is present.
    Add `spectre-recording-macos`, `spectre-recording-linux`, and/or
    `spectre-recording-windows` as runtime-only dependencies for those helpers. See
    [Recording limitations](../RECORDING-LIMITATIONS.md) for the per-platform notes.

!!! note "Linux migration note"
    Linux Xorg/Xvfb recording and screenshots now use the bundled
    `spectre-recording-linux` helper with GStreamer. The previous Linux ffmpeg/x11grab
    fallback is no longer used by `AutoRecorder` or `AutoScreenshotter`; `ffmpeg` on
    `PATH` is only relevant if you instantiate the explicit legacy ffmpeg backends.

!!! note "Windows migration note"
    Windows window, region, and fullscreen recording now use the
    `spectre-recording-windows` helper through Windows Graphics Capture. `AutoRecorder`
    no longer falls back to ffmpeg/gdigrab when the helper artifact is absent or when
    Windows-incompatible options such as custom `RecordingOptions.codec` or `screenIndex`
    are supplied; those cases fail at `start(...)` with an actionable error. `ffmpeg` on
    `PATH` is only relevant if you instantiate the explicit legacy ffmpeg backends.

!!! note "macOS migration note"
    macOS window and region recording now use the `spectre-recording-macos`
    ScreenCaptureKit helper. `AutoRecorder` no longer falls back to ffmpeg/avfoundation
    when that helper artifact is absent or when SCK-incompatible options such as a custom
    `RecordingOptions.codec` are supplied; those cases fail at `start(...)` with an
    actionable error. `ffmpeg` on `PATH` is only relevant if you instantiate the explicit
    legacy ffmpeg backends.

!!! note "macOS TCC preflight (v1)"
    Capture and recording **never** pop the Screen Recording TCC prompt implicitly.
    The helper preflights with `CGPreflightScreenCaptureAccess` and fails fast with a
    structured error (Settings path + deep link) when access is missing. Agents cannot
    click TCC prompts. With a human present, run `spectre permissions check` or
    `spectre permissions request` (the only path that may call
    `CGRequestScreenCaptureAccess`).

## Still Window Screenshots

`ComposeAutomator.screenshot(...)` lives in `spectre-core` and captures a rectangle
from the current screen framebuffer. When you have a top-level AWT window and want a
window-scoped still image, use `AutoScreenshotter` from `spectre-recording`:

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
| macOS | ScreenCaptureKit helper (`spectre-screencapture --mode screenshot`) | Captures the window source, not overlapping apps | Requires `spectre-recording-macos` at runtime and Screen Recording permission |
| Windows | Windows Graphics Capture helper (`spectre-window-capture.exe`) | Captures the named top-level window | Requires `spectre-recording-windows`, Windows 10 version 1903 or newer, .NET 8 Desktop Runtime, Windows App Runtime 1.8, and a non-blank exact window title |
| Linux Xorg/Xvfb | Linux helper + GStreamer `ximagesrc` | Captures visible screen pixels | The target window must be visible/frontmost, same limitation as `ComposeAutomator.screenshot(...)`; window mode needs a non-blank exact title |
| Linux Wayland | Linux helper + portal/PipeWire one-frame capture | Portal-scoped; region mode captures the selected monitor stream, window mode captures the selected window stream | Requires the compositor portal dialog to be accepted. Window mode needs `xprop` and `_GTK_FRAME_EXTENTS`, same as `WaylandPortalWindowRecorder` |

Embedded `ComposePanel` surfaces without their own top-level OS window still need the
**host** top-level window for still screenshots and video. Prefer capturing that host
window (optionally cropped to the panel) over framebuffer region shots when the host
exposes a titled `Frame`.

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
  confirm it contains only the smoke window; ffmpeg is not required for still screenshots,
  but runtime users need .NET 8 Desktop Runtime and Windows App Runtime 1.8 installed.
- **Linux Xorg/Xvfb** — run from a real Xorg/Xvfb display with `DISPLAY` set and
  `spectre-recording-linux` on the runtime classpath. The task uses the Linux helper's
  GStreamer `ximagesrc` path, so keep the smoke window visible/frontmost. Open the printed
  PNG and confirm it contains the smoke window. Do not force this smoke through XWayland
  inside a native Wayland compositor: Mutter's XWayland root framebuffer is black even though
  the pointer is visible. Normal Wayland sessions should validate the portal route below.
- **Linux Wayland** — run with `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`, and the D-Bus session
  bus visible to the JVM. The task asks the compositor portal for a window stream and writes
  a one-frame PNG after you accept the dialog. If the dialog is hidden or rejected, the helper
  fails loudly with the portal error or timeout. To test window-targeted Wayland video, run:

  ```bash
  ./gradlew :recording:runWaylandPortalWindowSmoke
  ```

  Pick the smoke window in the compositor portal dialog and inspect the resulting video.

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

You start a recording, you get a `RecordingHandle`, you stop the handle when you're
done. Implementations spawn the underlying process eagerly and fail fast when startup
is clearly broken. Some process-backed recorders still cannot prove that the first frame
has already landed in `output` before `start()` returns.

## Recorder capability matrix

Six concrete recorders ship today. `AutoRecorder` (next section) picks one of them per
call based on platform and whether you pass a window; the matrix below is the per-recorder
view for when you want to know what each backend can and cannot do.

| Recorder | Capture shape | Follows window resize? | Captures occluding windows / popups? | Prerequisites | Platforms |
| --- | --- | --- | --- | --- | --- |
| `FfmpegRecorder` (deprecated) | Screen region (fixed `Rectangle`) | No — region fixed at `start()` | Yes — anything visible inside the region lands in the frame, including occluding apps and overlapping Compose `OnWindow` popups | `ffmpeg` on `PATH`; macOS Screen Recording TCC for the launching app | macOS · Windows legacy/explicit · Linux X11 legacy/explicit |
| `WindowsGraphicsCaptureRecorder` | Named window (exact title + owner pid), optional pre-encode crop, or screen region | Window mode follows the target window across moves; optional crop is **fixed at start** (v1). Region mode is fixed at `start()` | Window mode (with or without crop) captures only the target window's pixels — occluders excluded. Region/fullscreen mode captures visible monitor pixels including overlapping windows and popups | `spectre-recording-windows` runtime helper (`spectre-window-capture.exe`); Windows 10 version 1903 or newer; .NET 8 Desktop Runtime; Windows App Runtime 1.8 | Windows |
| `FfmpegWindowRecorder` (deprecated) | Legacy named window (`gdigrab title=`) | Yes — `gdigrab` retracks the window across moves and resizes | No — only the window's pixels are captured. Compose `OnWindow` popups land in a separate OS window with a different title, so they are **not** captured. `OnSameCanvas` / `OnComponent` popups are part of the window surface and are captured | `ffmpeg` on `PATH`; visible window with a non-blank exact title | Windows |
| `ScreenCaptureKitRecorder` | Named window (pid + title substring), optional window-relative crop, or screen region | Window mode follows moves/resizes of the host window; optional `sourceRect` crop is **fixed at start** (v1). Region mode is fixed at `start()` | Window mode (with or without crop) captures only the window's pixels — occluders excluded. Region mode captures visible display pixels including overlapping apps | `spectre-recording-macos` runtime helper (`spectre-screencapture`); macOS Screen Recording TCC for the launching app | macOS |
| `LinuxX11Recorder` | Screen region or named X11 window (`ximagesrc`) | Region mode is fixed at `start()`; window mode uses GStreamer's `xname` source selection | Captures visible X server pixels for the selected source; occluding apps can appear in region capture | `spectre-recording-linux` runtime helper; `gst-launch-1.0` with `ximagesrc`, `videorate`, `x264enc`, and `mp4mux`; `DISPLAY` | Linux Xorg/Xvfb; named XWayland windows only when the process is deliberately forced onto the X11 backend |
| `WaylandPortalRecorder` | Monitor region (portal `SourceType.MONITOR`, cropped) | No — monitor-level source | Depends on the compositor. Validated on GNOME/Mutter to include the user's overlays but exclude apps occluding the recorded region | `spectre-recording-linux` runtime helper (`spectre-wayland-helper`); `xdg-desktop-portal` + PipeWire; `gst-launch-1.0` on `PATH`. First call pops the compositor's "share screen" dialog | Linux Wayland |
| `WaylandPortalWindowRecorder` | Named window (portal `SourceType.WINDOW` + fixed crop) | **No.** The crop is computed once from `_GTK_FRAME_EXTENTS` at `start()` and stays at those pixel coordinates for the rest of the recording. If the user moves or resizes the window during the recording, the crop no longer aligns with the window's pixels (typically the recording shows blank / black for the unrendered area of the original rectangle) — see `WaylandPortalWindowRecorder`'s KDoc for the detailed rationale | No — only the picked window's pixels are in the stream. `OnWindow` popups not captured | `spectre-recording-linux` runtime helper; `xdg-desktop-portal` + PipeWire; `gst-launch-1.0` on `PATH`; `xprop` on `PATH`; compositor must publish `_GTK_FRAME_EXTENTS` (GNOME/Mutter verified; older Mutter / KDE / sway may not — the recorder throws rather than producing misaligned video) | Linux Wayland |

A note on the Wayland window row: window-source isolation (no leakage from other apps) is
what makes window-targeted Wayland capture different from ffmpeg-on-X11 region capture. The
crop is fixed at `start()`, so the recording is robust against occluders but not against the
user moving or resizing the window mid-recording.

Embedded-host implications: a `ComposePanel` inside a host top-level window (including
Jewel-hosted Compose in an IntelliJ tool window) should use **window capture + crop**, not
region capture. Pass the host window as a `TitledWindow` and the panel's bounds relative to
that window (AWT user space — same as window-identity `surfaceBoundsInWindow`) via
`AutoRecorder.startWindow(..., cropInWindow = ..., scaleX = ..., scaleY = ...)`. macOS uses
`SCStreamConfiguration.sourceRect`; Windows crops pre-encode in the WGC helper. The crop is
**fixed at start** in v1 — surface move/resize mid-recording is not followed (a stderr
warning is emitted). Linux X11/Wayland window+crop is not implemented yet; those platforms
fail loudly rather than silently falling back. Use `startRegion(...)` only as an
**explicit last resort** (occlusion-sensitive, fixed screen rectangle).

## `AutoRecorder` — pick a backend per call

`AutoRecorder` is the entry point you should reach for first. It looks at what you pass
and picks the appropriate backend:

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

`TitledWindow` is an interface. The production adapter is the `Frame.asTitledWindow()`
extension shown above; tests typically wire a small in-memory implementation.

Use `startRegion(...)` when you want an explicit screen rectangle instead of a
window-targeted source:

```kotlin
val handle = recorder.startRegion(
    region = Rectangle(100, 100, 800, 600),
    output = Path.of("build/recordings/my-test.mp4"),
    options = RecordingOptions(),
)
```

The two paths do not silently fall back to each other. If `startWindow(...)` cannot use
a true window-targeted backend on the current platform, it throws with remediation
guidance; call `startRegion(...)` explicitly if region capture semantics are acceptable.

The routing is platform-keyed. Read the row that matches your OS:

| Platform                | `window`                          | Backend chosen                                                       |
| ----------------------- | --------------------------------- | -------------------------------------------------------------------- |
| **macOS**               | non-null                          | `ScreenCaptureKitRecorder` (`spectre-recording-macos` helper).       |
| **macOS**               | `null`                            | `ScreenCaptureKitRecorder` region capture (`spectre-recording-macos` helper). |
| **Windows**             | non-null with a non-blank title   | `WindowsGraphicsCaptureRecorder` (`spectre-recording-windows` helper). |
| **Windows**             | `null`, or non-null with no title | `WindowsGraphicsCaptureRecorder` region capture.                     |
| **Linux Xorg/Xvfb**     | non-null with a non-blank title   | `LinuxX11Recorder` window capture (`ximagesrc xname`).               |
| **Linux Xorg/Xvfb**     | `null`, or non-null with no title | `LinuxX11Recorder` region capture (`ximagesrc`).                     |
| **Linux Wayland**       | non-null                          | `WaylandPortalWindowRecorder` (portal `Window` source type — only the picked window's pixels, window-sized output). |
| **Linux Wayland**       | `null`                            | `WaylandPortalRecorder` (portal `Monitor` source type — region capture). |

A few details worth knowing:

- **macOS SCK helper is required for AutoRecorder.** If the Swift helper isn't present on
  the runtime classpath, macOS window and region recording fail loudly instead of falling
  back to `FfmpegRecorder`/`avfoundation`. Operational SCK failures — permission denied,
  target window not found, helper crashed during init — propagate as exceptions, so you see
  the real cause. Region capture supports `RecordingOptions.screenIndex` as the SCK display
  index: primary display first, then by display frame `minX` and `minY`. Custom codec
  strings are rejected because the helper writes H.264 through `AVAssetWriter`.
- **Windows WGC helper is required for AutoRecorder.** If `spectre-recording-windows`
  is absent at runtime, `AutoRecorder.startRegion(...)` fails loudly instead of
  falling back to legacy `FfmpegRecorder`/`gdigrab`. Windows options that WGC cannot
  honour, such as a custom `RecordingOptions.codec` or `screenIndex`, also fail
  rather than changing backends. Window-targeted recording, fullscreen recording
  through `WindowsGraphicsCaptureRecorder`, and still window screenshots all require
  the helper artifact; operational WGC failures propagate.
- **Linux Xorg/Xvfb uses the Linux helper by default.** `AutoRecorder` no longer uses
  `ffmpeg` for the Linux Xorg/Xvfb route. Region and window capture go through the
  bundled Rust helper from `spectre-recording-linux`, which spawns `gst-launch-1.0`
  with `ximagesrc`. The explicit `FfmpegRecorder`, `FfmpegWindowRecorder`,
  `FfmpegRegionScreenshotter`, and `FfmpegWindowScreenshotter` classes are deprecated legacy
  escape hatches; use them only if you intentionally need the old ffmpeg backends. The helper's recording pipeline currently supports
  `RecordingOptions.codec = "libx264"` or `"x264enc"` only; arbitrary GStreamer encoder
  strings are rejected with a clear error until Spectre grows a structured encoder
  pipeline configuration.
- **Linux Wayland always uses the portal** (when a portal recorder is wired up),
  regardless of whether `window` is null, because X11 capture is not valid for native
  Wayland sessions. With `window != null` the router picks the window-targeted portal
  recorder (`WaylandPortalWindowRecorder`, `SourceType.WINDOW`): the dialog asks the
  user to pick a window, the granted PipeWire stream contains only that window's pixels
  (no leakage from occluding apps the way region capture suffers from), and the helper
  crops to the window's pixel size. With `window == null` (e.g., embedded `ComposePanel`
  capture) the router uses the region recorder (`WaylandPortalRecorder`,
  `SourceType.MONITOR`): the dialog asks for a monitor, and the helper crops the
  monitor stream to the requested rectangle. The first call pops the compositor's
  "share your screen" dialog; subsequent calls in the same JVM run reuse the grant.
- **Linux helper.** Both portal recorders run the same small Rust binary
  (`spectre-wayland-helper`) from the `spectre-recording-linux` runtime artifact. The
  same binary also owns Linux Xorg/Xvfb capture. On Wayland it drives
  `xdg-desktop-portal`'s ScreenCast interface and hands a PipeWire FD to
  `gst-launch-1.0`; on Xorg/Xvfb it runs GStreamer's `ximagesrc` directly.
- **Window-targeted Wayland needs `xprop`.** `WaylandPortalWindowRecorder` queries the
  X11 `_GTK_FRAME_EXTENTS` property on the JFrame's XWayland window via the `xprop`
  binary to compute the right stream-relative crop (Mutter renders window-source streams
  with a ~25 px GTK shadow margin around the visible window that AWT's `frame.getBounds()`
  doesn't see, so without the extents the close button gets clipped). `xprop` is part of
  `x11-utils` on Debian/Ubuntu — installed by default on the desktop image, separate
  package on minimal images. If `xprop` isn't available or the window's WM doesn't
  publish `_GTK_FRAME_EXTENTS` (older Mutter, non-GTK CSD, KDE / sway with server-side
  decorations), the recorder throws `IllegalStateException` rather than producing a
  misaligned mp4. Installing `xprop` only fixes the missing-binary case; Qt, JavaFX,
  Electron, SDL, and other non-GTK windows may still lack the property. Use explicit
  region capture instead by calling `startRegion(...)`. See
  [Recording limitations](../RECORDING-LIMITATIONS.md#platform) for more.

## Lower-level backends

If you know exactly which backend you want, instantiate it directly and skip the router:

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

macOS has the most involved setup; the others are short. macOS first:

### macOS

- Add `dev.sebastiano.spectre:spectre-recording-macos:<version>` as a runtime-only
  dependency. Its jar carries the notarized universal ScreenCaptureKit helper at
  `native/macos/spectre-screencapture`; the base `spectre-recording` artifact does not
  bundle this helper.
- **Screen Recording TCC permission**, granted under System Settings → Privacy &
  Security → Screen Recording.
- **An unlocked console session.** A locked macOS screen can make screenshots or
  recordings fail, or produce black frames, even when Screen Recording TCC is already
  granted.

macOS attributes TCC to the **responsible parent process** — the binary that
launched the JVM, not `java` itself. Grant the permission to whichever app opened
the test JVM:

- Running tests from IntelliJ IDEA → grant **IntelliJ IDEA**.
- `./gradlew test` from a terminal → grant **Terminal.app** (or **iTerm**, etc.).
- A standalone `java` invocation from a third-party launcher → grant **that
  launcher**.
- On CI (e.g., GitHub Actions macOS runners) → the runner image either needs to be
  pre-granted or use a notarised wrapper that has its own TCC entry.

macOS doesn't refresh TCC for already-running processes, so after granting, fully
quit and relaunch the parent app — not just the JVM child — for the permission to
take effect. The typical first-time flow is "run, fail, grant, relaunch parent app,
run again". See
[Troubleshooting](troubleshooting.md#macos-recording-errors-out-or-produces-no-file)
for failure modes when permission is missing or attached to the wrong binary.

Spectre extracts the ScreenCaptureKit helper from `spectre-recording-macos` before running
it. By default, it uses a stable per-user path:

```text
~/Library/Application Support/spectre/helpers/spectre-screencapture/<helper-hash>/spectre-screencapture
```

Grant Screen Recording to that helper once in System Settings. Spectre includes a short helper
content hash in the path, so native-helper fixes in future Spectre versions get a fresh extracted
binary instead of silently reusing stale bytes. Later runs of the same helper binary re-extract to
the same path, so macOS continues to recognise it.

If you prefer a project-specific helper location, set the extraction directory before the test JVM
starts:

```kotlin
systemProperty(
    "spectre.recording.screencapturekit.helperDir",
    layout.buildDirectory.dir("spectre-helper").get().asFile.absolutePath,
)
```

Then grant Screen Recording to `<helperDir>/spectre-screencapture` instead. If `./gradlew clean`
removes that file, the TCC grant still applies the next time Spectre extracts the helper to the
same path.

`apple.awt.UIElement=true` helper/test JVMs are useful with `RobotDriver.synthetic(...)`
for focus-safe per-character `typeText`, but clipboard-backed `pasteText` and recording
still go through macOS services outside Spectre's synthetic key path. Prefer a normal
foreground-capable parent process for recording tests, especially while establishing Screen
Recording TCC grants. See [Running on CI](ci.md#macos-helper-jvms) for the CI-side
trade-offs.

### Other platforms

Spectre extracts bundled native helpers to stable per-user directories on every platform:
`~/Library/Application Support/spectre/helpers` on macOS, `%LOCALAPPDATA%\\spectre\\helpers`
on Windows, and `$XDG_CACHE_HOME/spectre/helpers` or `~/.cache/spectre/helpers` on Linux.

- **Windows** — add `dev.sebastiano.spectre:spectre-recording-windows:<version>` as a
  runtime-only dependency for native window recording, region/fullscreen recording, and
  still window screenshots. Runtime machines need Windows Graphics Capture support
  (Windows 10 version 1903 or newer), .NET 8 Desktop Runtime, and Windows App Runtime
  1.8; contributors and CI building that helper from source need the .NET 8 SDK.
  `AutoRecorder.startRegion(...)` requires this helper; `ffmpeg` is only needed if you
  instantiate the explicit legacy ffmpeg backends.
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

For the full set of trade-offs (frame drop behaviour, minimum dimensions, crop
pitfalls, audio support) see [Recording limitations](../RECORDING-LIMITATIONS.md).

## Stopping cleanly

`RecordingHandle.stop()` is synchronous and waits for the encoder to flush. If your
test fails before reaching the stop call, wrap the recording in `try`/`finally`:

```kotlin
val handle = recorder.startWindow(/* ... */)
try {
    // test body
} finally {
    handle.stop()
}
```

Otherwise, the spawned `ffmpeg` (or helper, or `gst-launch`) keeps writing until it's
killed, and you may end up with truncated or unfinalised output files.

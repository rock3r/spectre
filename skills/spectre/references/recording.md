# Recording and screenshots

The `:recording` module captures MP4 video and native window-scoped still screenshots from a running Compose Desktop UI.
`ComposeAutomator.screenshot(node)`, `screenshot(windowIndex)`, `screenshotWindows(...)`, and `capture(windowIndex)`
require it; `screenshot(region)` and `screenshotAtDeviceScale(region)` use `java.awt.Robot` directly without
`:recording`. **Audio is not supported** (video-only).

## Native window stills and `AutoScreenshotter`

`screenshot(node)`, `screenshot(windowIndex)`, `capture(windowIndex)`, and JUnit's automatic failure-artefact window
captures all use the native window backend and **fail closed** when that backend cannot run or cannot uniquely identify
the window — they never substitute a `Robot` desktop crop. Use `AutoScreenshotter.captureWindow(frame.asTitledWindow())`
when calling the top-level window still API directly outside `ComposeAutomator`.

Window selection is scoped to the owner process. A unique title is only a filter; shared or
untitled local windows are selected by their on-screen frame. If identity and geometry still match
more than one window, capture fails as ambiguous. Spectre never changes the application's title.

| Platform | Still backend | Runtime helper artefact |
|---|---|---|
| macOS | ScreenCaptureKit still-image mode (`ScreenCaptureKitScreenshotter`) | `spectre-recording-macos` |
| Windows | Windows Graphics Capture (`WindowsWindowScreenshotter` / `spectre-window-capture.exe`) | `spectre-recording-windows` |
| Linux X11/XWayland | Linux GStreamer helper (`ximagesrc`; target must be visible/frontmost) | `spectre-recording-linux` + GStreamer (`gst-launch-1.0`) |
| Linux Wayland | GNOME Mutter window stream via `spectre-wayland-helper` | `spectre-recording-linux` (GNOME only; requires the one-time `spectre permissions request` grant) |

### Untitled `JWindow` popups and multi-window stills

- On macOS, Windows, and Linux X11, `screenshot(windowIndex)`, `screenshot(node)`, and `waitForVisualIdle()` also
  support popups hosted in an untitled `JWindow` by matching window position and size (two windows with identical bounds
  fail closed as ambiguous). Recording (`AutoRecorder.startWindow`) still requires a titled window.
- On Linux Wayland, popups that are not a `Frame` or `Dialog` (e.g., `JWindow`) throw `UnsupportedOperationException`
  (`#640`), causing `waitForVisualIdle()` to fail immediately while open; capture the owner window, use
  `screenshot(region)`, or run under X11/Xvfb.
- When Swing/Jewel dialogs, menus, or tooltips open in separate AWT windows extending beyond the owner, call
  `automator.screenshotWindows(listOf(0, 1))` in back-to-front painter's order (owner before popup). It composites
  per-window captures onto their union bounds (`CompositeScreenshot.composite` is `null` if any window fails, while
  successful `WindowScreenshotResult.Success` entries remain accessible).

### Linux X11 `xvfb-run` and `waitForVisualIdle` vs fail-closed stills

Under `xvfb-run` (with `-Djava.awt.headless=false`), `screenshot(region)` and `screenshotAtDeviceScale(region)` need
only `Robot`. When `spectre-recording` is on the classpath, `screenshot(node)`, `screenshot(windowIndex)`, `capture`,
`waitForVisualIdle`, and JUnit failure-artefact captures go through the Linux GStreamer window bridge:

- `screenshot(node)`, `screenshot(windowIndex)`, `capture`, and failure-artefact window captures fail closed when
  GStreamer (`gst-launch-1.0`) is missing. Install GStreamer on Linux X11 CI runners if failing tests should save
  per-window PNG artefacts.
- `waitForVisualIdle` falls back to `Robot` region capture when `:recording` is absent or the native helper/GStreamer
  binary cannot launch, whereas other native capture failures (such as an occluded or ambiguous window) count as
  unsampleable frames and reset the stability streak until timeout.

### Windows prerequisites

Windows stills and `WindowsGraphicsCaptureRecorder` share the framework-dependent helper in `spectre-recording-windows`
(x64 and arm64). Add `testRuntimeOnly("dev.sebastiano.spectre:spectre-recording-windows")` and install Windows 10 1903+,
.NET 8 Desktop Runtime, and Windows App Runtime 1.8. Building from source also requires the .NET 8 SDK; verify with:

```shell
./gradlew :recording-windows:verifyRecordingWindowsHelper
```

### macOS console lock and display order

macOS capture requires both Screen Recording TCC permission and an unlocked console session. A locked screen makes
`Robot.createScreenCapture` return black pixels; Spectre checks `IOConsoleLocked` first and reports a locked console
before pointing at TCC. For `AutoRecorder.startRegion`, `RecordingOptions.screenIndex` uses ScreenCaptureKit ordering:
display `0` is primary, then remaining displays sorted by frame `minX`/`minY` (legacy `FfmpegRecorder` callers still use
avfoundation ordering).

## Video recording — `AutoRecorder`

| Platform | `startWindow` | `startRegion` |
|---|---|---|
| macOS | ScreenCaptureKit (native) | ScreenCaptureKit helper; missing helper artefacts or SCK-unsupported options (`codec`) fail closed (no ffmpeg/avfoundation fallback) |
| Windows | Windows Graphics Capture helper | Windows Graphics Capture helper; missing helper artefacts or WGC-unsupported options (`codec`, `screenIndex`) fail closed (no ffmpeg fallback) |
| Linux X11/XWayland | Linux GStreamer helper | Linux GStreamer helper |
| Linux Wayland (GNOME/Mutter) | GNOME Mutter window stream (needs one-time grant; no per-window dialog) | `xdg-desktop-portal` monitor source (one-time grant) |

```kotlin
val recorder = AutoRecorder()
val handle = recorder.startWindow(
    window = composeWindow.asTitledWindow(),
    output = Path.of("build/recordings/my-test.mp4"),
    options = RecordingOptions(),
)
try {
    // drive the UI here
} finally {
    handle.stop()
}
```

Always stop the handle inside `finally` so failing tests still finalise the MP4.

### Window vs region targeting

- **Region (`startRegion`)**: fixed screen `Rectangle`. Does not follow window moves/resizes; popups outside the
  rectangle are clipped. Required for embedded `ComposePanel` hosts that lack a top-level title.
- **Window (`startWindow`)**: follows window moves, fails closed when unavailable, and omits popups rendered in separate
  AWT windows outside the target. Choose an explicit `startRegion` wide enough for cross-window popups when needed.

## Linux Wayland consent and limits

A human at the seat approves once via `spectre permissions request` (select whole screen, tick Remember, click Share).
Spectre stores the grant and keeps one long-lived `spectre-wayland-helper` session at
`$XDG_RUNTIME_DIR/spectre/wayland-session.sock` for monitor capture and real OS input (`spectre permissions check` or
`WaylandPortalAccess.preflight()` inspects it without prompting).

- **Never retry in a loop in JUnit.** JUnit runs never open the portal dialog unless
  `-Dspectre.wayland.consentPrompt=allow` is explicitly set; without a stored grant they fail immediately naming
  `spectre permissions request`.
- Explicit generic portal window requests fail before prompting or restoring a grant. The picker cannot identify the requested window.
- **GNOME Mutter window streams** power window stills, visual-idle samples, and `startWindow` through the same helper
  session (so they still require the one-time grant). On non-GNOME Wayland desktops, use `startRegion(...)`,
  `screenshot(region)`, or X11/Xvfb.
- **First window capture off EDT:** Spectre briefly activates the window on its first capture and restores focus; do not
  call it on the AWT EDT.
- **Real OS input:** always use no-arg `RobotDriver()`; `RobotDriver(robot)` bypasses the portal session via Xwayland.

User guide: [Linux Wayland consent](https://spectre.sebastiano.dev/guide/recording/#linux-wayland-consent).

## HiDPI coordinates, device-scale crops, and CI cost

- `Rectangle` regions and `AutomatorNode.boundsOnScreen` use **logical screen pixels** (already divided by display scale
  plus the Compose surface's screen origin). `AutomatorNode.boundsInWindow` uses **density-scaled surface pixels**
  relative to the Compose surface; never multiply `boundsInWindow` by display density, and include the surface screen
  origin if converting manually.
- When `screenshot(node)` is unavailable (e.g., Linux Xvfb without GStreamer) and a test inspects sub-dp pixel geometry,
  capture `val img = automator.screenshotAtDeviceScale(node.boundsOnScreen)` and scale pixel thresholds by
  `img.width / node.boundsOnScreen.width` so 2× Retina and 1× Xvfb runs agree.
- Heavy CPU load can drop frames (stutter without corrupting the MP4; see `docs/RECORDING-LIMITATIONS.md`). Recording
  adds 5–30% CPU overhead and large files — enable on failure (`FailureVideoPolicy.OnFailureKeep`) or behind an env var
  rather than unconditionally across CI.

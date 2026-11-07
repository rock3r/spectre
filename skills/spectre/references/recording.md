# Recording and screenshots

The `:recording` module captures video and native window screenshots from a running
Compose Desktop UI. `ComposeAutomator.screenshot(node)` and `screenshot(windowIndex)`
need it for window capture. `screenshot(region)` captures an explicit screen rectangle.
**Audio is not supported.**

## Still window screenshots — `AutoScreenshotter`

Use `ComposeAutomator.screenshot(region)` for an explicit framebuffer rectangle.
`ComposeAutomator.screenshot(node)` and `screenshot(windowIndex)` use the native
window backend and fail clearly if it is unavailable or cannot identify the window.
Use `AutoScreenshotter.captureWindow(frame.asTitledWindow())` for a direct top-level
window still image.

`screenshot(windowIndex)`, `screenshot(node)`, and `waitForVisualIdle()` also work for popups
hosted in an untitled `JWindow`: the helper finds that window by its position and size. Two
windows with the same bounds make the capture fail. On Linux Wayland a popup that is not a `Frame`
or `Dialog` cannot be captured yet (`UnsupportedOperationException`, #640), so visual idle fails
at once while such a popup is open; capture the owner window, use `screenshot(region)`, or run
under X11/Xvfb. Recording still needs a titled window.

| Platform | Backend | Runtime helper |
|---|---|---|
| macOS | ScreenCaptureKit still-image mode | `spectre-recording-macos` |
| Windows | Windows Graphics Capture helper (`spectre-window-capture.exe`) | `spectre-recording-windows` |
| Linux X11/XWayland | Linux helper (`ximagesrc`) | `spectre-recording-linux` |
| Linux Wayland | GNOME Mutter window stream through `spectre-wayland-helper` | `spectre-recording-linux`; GNOME only; needs the one-time `spectre permissions request` grant |

Windows stills and recording share a Windows Graphics Capture helper.
At runtime it needs Windows 10 version 1903 or newer, .NET 8 Desktop Runtime,
and Windows App Runtime 1.8. Building the helper needs the .NET 8 SDK.
Verify a local build with:

```bash
./gradlew :recording-windows:verifyRecordingWindowsHelper
```

## Entry point — `AutoRecorder`

`AutoRecorder` exposes explicit window and region capture entry points:

| Platform | `startWindow` | `startRegion` |
|---|---|---|
| macOS | ScreenCaptureKit (native) | ScreenCaptureKit helper; missing helper artefacts and SCK-unsupported options (`codec`) produce an error. There is no ffmpeg/avfoundation fallback. `screenIndex` is the SCK display index: primary first, then by frame `minX`/`minY` |
| Windows | Windows Graphics Capture helper | Windows Graphics Capture helper; missing helper artefacts and WGC-unsupported options (`codec`, `screenIndex`) produce an error. There is no ffmpeg fallback |
| Linux X11/XWayland | Linux helper | Linux helper |
| Linux Wayland (GNOME/Mutter) | GNOME Mutter window stream (one-time grant, no dialog of its own) | xdg-desktop-portal monitor source (one-time grant) |

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

Use `try { … } finally { handle.stop() }` so a failing test still finalises the file.

## Window and region targeting

Choose capture by the source pixels the test needs:

- **Region (`startRegion`)**: fixed screen `Rectangle`. Does **not** follow
  the window if it moves or resizes; pixels outside the rectangle (popups,
  dialogs that escape the bounds) are lost. Use for a fixed crop when window
  placement is controlled.
- **Window-targeted**: follows the window. Does **not** capture popups that
  live in their own AWT window outside the target. Use for tests where the
  window may move while remaining the focus.

An embedded `ComposePanel` has no top-level title of its own.
Use `startRegion(...)` with an explicit rectangle when no host window is suitable.
`startWindow(...)` reports an error when window capture is unavailable.

For macOS region capture, `RecordingOptions.screenIndex` is interpreted by the SCK helper:
display `0` is the primary display, then remaining displays are sorted by frame `minX` and
`minY`. Explicit legacy `FfmpegRecorder` callers still get avfoundation device ordering.

macOS capture also requires an unlocked console session. If a smoke or test suddenly reports
black screenshots/frames or a permission-looking capture failure, unlock the screen and retry
before debugging TCC; Robot screenshot diagnostics check `IOConsoleLocked` for this case.

## Linux Wayland caveats

Approve once, then never again: a person at the seat runs `spectre permissions request`, picks
the whole screen, ticks Remember, and clicks Share. Spectre stores the grant and keeps one
long-lived `spectre-wayland-helper` session for monitor capture and real OS input. Later
processes reconnect through `$XDG_RUNTIME_DIR/spectre/wayland-session.sock` and open no dialog.
`spectre permissions check` (or `WaylandPortalAccess.preflight()`) reports the grant without a
dialog.

JUnit runs never open the dialog. Without a grant, the first capture or real input fails at once
with an error that names `spectre permissions request`. Ask a person to run it; do not retry in a
loop. `-Dspectre.wayland.consentPrompt=allow` lets a test run open the dialog anyway.

Window stills, visual-idle samples, and window recording use GNOME Mutter window streams. They
open no dialog and need no grant of their own, but they run through the same session helper, so
they also fail until the one-time approval exists. Limits:

- GNOME only. On another Wayland desktop, window capture fails; use `startRegion(...)` or
  `screenshot(region)`, or run under X11/Xvfb.
- Spectre focuses the window for its first capture and gives focus back. The first capture of a
  window must not run on the AWT event dispatch thread.
- Popups that are not a `Frame` or `Dialog` (for example a `JWindow`) cannot be captured yet
  (#640).
- Use the no-argument `RobotDriver()` for real input. `RobotDriver(robot)` bypasses the portal
  session.

User guide: [Linux Wayland consent](https://spectre.sebastiano.dev/guide/recording/#linux-wayland-consent).

## HiDPI and coordinates

`Rectangle` arguments are in **screen pixels** (post-HiDPI scaling), as is
`AutomatorNode.boundsOnScreen`. Prefer `boundsOnScreen`: it already divides by
the display scale and adds the surface/panel screen origin.
`AutomatorNode.boundsInWindow` is already in **Compose/device pixels**
(density-scaled) and window-relative. Manual conversion requires both
steps. Dividing alone lands near `(0, 0)` when the window is not at the
screen origin. Multiplying by density double-scales the region on a Retina
display.

## Frame drops

Heavy CPU load can cause frame drops. The MP4 can remain playable with visible
stutter. See `docs/RECORDING-LIMITATIONS.md` for platform behaviour.

## Recording cost on CI

Recording adds 5–30% CPU overhead and can produce large artefacts.
Enable it for selected tests or through an environment variable.

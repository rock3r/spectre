# Recording

The `recording` module adds video output to your tests. It exposes a small surface
backed by a handful of platform-specific implementations and a router that picks the
right one per call.

!!! note "External dependencies"
    The recording backends shell out to platform tools — `ffmpeg` everywhere, plus a
    bundled Swift helper on macOS and a small Rust helper on Linux Wayland. See
    [Recording limitations](../RECORDING-LIMITATIONS.md) for the per-platform notes.

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
done. Implementations must spawn the underlying process eagerly so frames are landing
in `output` by the time `start()` returns.

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
val handle = recorder.start(
    window = composeWindow.asTitledWindow(), // any java.awt.Frame works
    region = Rectangle(100, 100, 800, 600),
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

The routing is platform-keyed. Read the row that matches your OS:

| Platform                | `window`                          | Backend chosen                                                       |
| ----------------------- | --------------------------------- | -------------------------------------------------------------------- |
| **macOS**               | non-null                          | `ScreenCaptureKitRecorder` (bundled Swift helper).                   |
| **macOS**               | `null`                            | `FfmpegRecorder` region capture (`avfoundation`).                    |
| **Windows**             | non-null with a non-blank title   | `FfmpegWindowRecorder` (`gdigrab title=`).                           |
| **Windows**             | `null`, or non-null with no title | `FfmpegRecorder` region capture (`gdigrab`).                         |
| **Linux Xorg**          | any                               | `FfmpegRecorder` region capture (`x11grab`).                         |
| **Linux Wayland**       | non-null                          | `WaylandPortalWindowRecorder` (portal `Window` source type — only the picked window's pixels, window-sized output). |
| **Linux Wayland**       | `null`                            | `WaylandPortalRecorder` (portal `Monitor` source type — region capture). |

A few details worth knowing:

- **macOS SCK helper fallback.** If the Swift helper isn't bundled in the running
  JAR (e.g., you built on Linux but ran on macOS), the macOS + window path falls
  back to `FfmpegRecorder` region capture with a warning on stderr. Operational
  SCK failures — permission denied, target window not found, helper crashed during
  init — propagate as exceptions rather than falling back silently, so you see
  the real cause.
- **Linux Wayland always uses the portal** (when a portal recorder is wired up),
  regardless of whether `window` is null, because `LinuxX11Grab` refuses to run on
  Wayland sessions. With `window != null` the router picks the window-targeted portal
  recorder (`WaylandPortalWindowRecorder`, `SourceType.WINDOW`): the dialog asks the
  user to pick a window, the granted PipeWire stream contains only that window's pixels
  (no leakage from occluding apps the way region capture suffers from), and the helper
  crops to the window's pixel size. With `window == null` (e.g., embedded `ComposePanel`
  capture) the router uses the region recorder (`WaylandPortalRecorder`,
  `SourceType.MONITOR`): the dialog asks for a monitor, and the helper crops the
  monitor stream to the requested rectangle. The first call pops the compositor's
  "share your screen" dialog; subsequent calls in the same JVM run reuse the grant.
- **Linux Wayland helper.** Both portal recorders run the same small Rust binary
  (`spectre-wayland-helper`) bundled in the `recording` artifact. It drives
  `xdg-desktop-portal`'s ScreenCast interface and hands a PipeWire FD to
  `gst-launch-1.0`.
- **Window-targeted Wayland needs `xprop`.** `WaylandPortalWindowRecorder` queries the
  X11 `_GTK_FRAME_EXTENTS` property on the JFrame's XWayland window via the `xprop`
  binary to compute the right stream-relative crop (Mutter renders window-source streams
  with a ~25 px GTK shadow margin around the visible window that AWT's `frame.getBounds()`
  doesn't see, so without the extents the close button gets clipped). `xprop` is part of
  `x11-utils` on Debian/Ubuntu — installed by default on the desktop image, separate
  package on minimal images. If `xprop` isn't available or the window's WM doesn't
  publish `_GTK_FRAME_EXTENTS` (older Mutter, non-GTK CSD, KDE / sway with server-side
  decorations), the recorder throws `IllegalStateException` rather than producing a
  misaligned mp4. Fall back to `WaylandPortalRecorder` (region capture) — pass
  `window = null` and the router selects it automatically. See
  [Recording limitations](../RECORDING-LIMITATIONS.md#platform) for more.

## Lower-level backends

If you know exactly which backend you want, instantiate it directly and skip the router:

| Backend                       | Use it for                                                                |
| ----------------------------- | ------------------------------------------------------------------------- |
| `FfmpegRecorder`              | Region capture on macOS, Windows, and Linux Xorg. (Throws on Wayland — use `WaylandPortalRecorder` there.) Default for "no window in mind". |
| `FfmpegWindowRecorder`        | Windows-only window-targeted capture via `gdigrab title=`.                |
| `ScreenCaptureKitRecorder`    | macOS-only window-targeted capture via the bundled Swift helper.          |
| `WaylandPortalRecorder`       | Linux Wayland region capture via `xdg-desktop-portal` (`SourceType.MONITOR`) and a bundled Rust helper. |
| `WaylandPortalWindowRecorder` | Linux Wayland window-targeted capture via `xdg-desktop-portal` (`SourceType.WINDOW`). Window-sized output containing only the picked window's pixels. |

## Per-OS prerequisites

macOS has the most involved setup; the others are short. macOS first:

### macOS

- `ffmpeg` on `PATH`.
- The Swift ScreenCaptureKit helper is bundled inside `recording`'s artifact.
  Universal-binary opt-in is available via
  `./gradlew :recording:assembleScreenCaptureKitHelper -PuniversalHelper`.
- **Screen Recording TCC permission**, granted under System Settings → Privacy &
  Security → Screen Recording.

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

### Other platforms

- **Windows** — `ffmpeg` on `PATH`. `gdigrab` ships with `ffmpeg`.
- **Linux Xorg** — `ffmpeg` with the `x11grab` input enabled (default in distro builds).
- **Linux Wayland** — `gst-launch-1.0` plus the GStreamer plugins for H.264/Matroska.
  The Rust helper is bundled inside `recording`'s artifact. Recording requires a
  Wayland compositor that exposes `org.freedesktop.portal.ScreenCast`; tested against
  GNOME/mutter on Ubuntu 22.04 and 24.04.

For the full set of trade-offs (frame drop behaviour, minimum dimensions, crop
pitfalls, audio support) see [Recording limitations](../RECORDING-LIMITATIONS.md).

## Stopping cleanly

`RecordingHandle.stop()` is synchronous and waits for the encoder to flush. If your
test fails before reaching the stop call, wrap the recording in `try`/`finally`:

```kotlin
val handle = recorder.start(/* ... */)
try {
    // test body
} finally {
    handle.stop()
}
```

Otherwise, the spawned `ffmpeg` (or helper, or `gst-launch`) keeps writing until it's
killed, and you may end up with truncated or unfinalised output files.

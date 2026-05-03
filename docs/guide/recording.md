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

Routing logic, in order:

1. **Linux Wayland session with a portal recorder wired up** → `WaylandPortalRecorder`,
   regardless of whether `window` is null. The portal flow handles both cases by
   capturing the user-picked monitor and cropping to the requested region. This branch
   wins first because Wayland can't fall through to `x11grab` — `LinuxX11Grab` throws on
   Wayland sessions. The portal recorder runs a Rust helper
   (`spectre-wayland-helper`) that drives `xdg-desktop-portal`'s ScreenCast interface
   and hands a PipeWire FD to `gst-launch-1.0`. First call pops a permission dialog;
   subsequent calls in the same JVM run reuse the grant.
2. **`window == null`** (non-Wayland) → `ffmpeg` region capture. Use this shape when
   there's no clear window to target (e.g., a `ComposePanel` embedded in a Swing host).
3. **macOS + a window** → bundled ScreenCaptureKit helper (`WindowRecorder`). The
   helper is a tiny Swift binary owned by Spectre. If the helper isn't bundled in the
   running JAR (e.g., you built on Linux but ran on macOS), it falls back to `ffmpeg`
   region capture and warns on stderr. Operational SCK failures (permission denied, target
   not found, helper crashed) propagate as exceptions instead of falling back silently.
4. **Windows + a window with a non-blank title** → `FfmpegWindowRecorder` (`gdigrab
   title=` capture). Window movement is followed automatically; occlusion doesn't matter.
5. **Otherwise** → `ffmpeg` region capture. The backend resolves to `gdigrab` on Windows
   and `x11grab` on Linux Xorg.

## Lower-level backends

If you know exactly which backend you want, instantiate it directly and skip the router:

| Backend                       | Use it for                                                                |
| ----------------------------- | ------------------------------------------------------------------------- |
| `FfmpegRecorder`              | Region capture on macOS, Windows, and Linux Xorg. (Throws on Wayland — use `WaylandPortalRecorder` there.) Default for "no window in mind". |
| `FfmpegWindowRecorder`        | Windows-only window-targeted capture via `gdigrab title=`.                |
| `ScreenCaptureKitRecorder`    | macOS-only window-targeted capture via the bundled Swift helper.          |
| `WaylandPortalRecorder`       | Linux Wayland-only via `xdg-desktop-portal` and a bundled Rust helper.    |

## Per-OS prerequisites

- **macOS** — `ffmpeg` on `PATH`. The Swift ScreenCaptureKit helper is bundled inside
  `recording`'s artifact; you also need to grant Screen Recording permission to the JVM
  the first time you record. Universal-binary opt-in for the helper is available via
  `./gradlew :recording:assembleScreenCaptureKitHelper -PuniversalHelper`.
- **Windows** — `ffmpeg` on `PATH`. `gdigrab` ships with `ffmpeg`.
- **Linux Xorg** — `ffmpeg` with the `x11grab` input enabled (default in distro builds).
- **Linux Wayland** — `gst-launch-1.0` plus the GStreamer plugins for H.264/Matroska.
  The Rust helper is bundled inside `recording`'s artifact. Recording requires a
  Wayland compositor that exposes `org.freedesktop.portal.ScreenCast`; tested against
  GNOME/mutter on Ubuntu 22.04.

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

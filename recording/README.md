# Recording

Screen recording for Spectre scenarios. v1 region capture (ffmpeg) and v2 window-targeted
capture (ScreenCaptureKit) live side by side — pick by what you need.

## Public surface

### Region capture (v1)
- `Recorder` — interface; produces a `RecordingHandle` for an in-progress capture.
- `FfmpegRecorder` — v1 default. Shells out to a system `ffmpeg` and captures the requested
  region via the macOS avfoundation device. Resolves the binary via PATH (`resolveFfmpegPath()`)
  by default; pass `ffmpegPath` to override (testing, non-PATH installs).

### Window-targeted capture (v2, #18)
- `screencapturekit.ScreenCaptureKitRecorder` — forks a bundled Swift helper that drives
  `SCStream` + `AVAssetWriter` against a single window identified by `(pid, title-substring)`.
  Captures only that window's pixels even when partially occluded, and survives the host
  window moving across the screen. macOS only.

  Decision rationale + alternatives considered are written up in the bridge strategy doc kept
  in `.plans/`.

### Shared
- `RecordingHandle` — `AutoCloseable`. Stop sends `q` on stdin (the documented clean-shutdown
  signal for both ffmpeg and the ScreenCaptureKit helper), waits up to 5 s for a graceful exit,
  then SIGTERM / SIGKILL fallback.
- `RecordingOptions` — frame rate, cursor capture, codec.
- `MacOsRecordingPermissions.diagnose()` — best-effort startup diagnostic for Screen Recording
  and Accessibility permissions. Full native detection (`CGPreflightScreenCaptureAccess`,
  `AXIsProcessTrusted`) is still deferred — the v2 helper detects TCC denial by exit code,
  which is the practical signal callers need.

## Capability matrix

| Capability | Status |
|---|---|
| macOS region capture (avfoundation) | ✅ `FfmpegRecorder` |
| Embedded `ComposePanel` (`windowHandle == 0L`) | ✅ region capture (window-targeted not applicable) |
| ScreenCaptureKit window-targeted capture | ✅ `ScreenCaptureKitRecorder` |
| Windows `gdigrab` recording | ⏸ v3 (#22) |
| Linux `x11grab` / pipewire recording | ⏸ v4 (#27) |
| Audio | ⏸ deferred — add when asked |
| Notarization of the SCK helper | ⏸ deferred — local dev relies on Screen Recording grant inheriting from the host JVM |

## ScreenCaptureKit helper build

`recording/native/macos/` is a SwiftPM project that produces the `spectre-screencapture`
helper binary. The `assembleScreenCaptureKitHelper` Gradle task runs `swift build -c release`
and stages the binary into `src/main/resources/native/macos/`, so the JAR carries it
transparently. The Swift `swift build` step only runs on macOS hosts — non-macOS hosts
produce a structurally-correct jar that just doesn't contain the helper file (consumers see
`HelperBinaryExtractor`'s "binary not found" message at runtime if they try to use SCK).

**Distribution must be built on macOS.** A jar published from a Linux CI runner won't carry
the helper, so any macOS consumer of that jar would fail with "helper binary not found" the
first time `ScreenCaptureKitRecorder.start()` runs. A future macOS CI workflow will guard
against this — see the v2 follow-ups doc.

For local dev the helper is built single-arch (host arch only — universal builds need full
Xcode for `xcbuild`, which CLT alone doesn't ship). A separate release task that produces the
universal `arm64+x86_64` binary will land alongside notarization.

### Manual end-to-end smoke
After granting the host JVM Screen Recording permission, point a `ScreenCaptureKitRecorder`
at any titled window owned by the same JVM:

```kotlin
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorder
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow

val recorder = ScreenCaptureKitRecorder()
val handle = recorder.start(
    window = composeWindow.asTitledWindow(), // public adapter for any java.awt.Frame
    output = Paths.get("/tmp/spectre-sck.mov"),
)
// drive the UI...
handle.stop() // restores the original title, finalises the .mov
```

`asTitledWindow()` is a `Frame.() -> TitledWindow` extension that works against
`ComposeWindow`, `JFrame`, `JDialog.parent`, or any other `java.awt.Frame`. If you have a
non-Frame window-like object, implement `TitledWindow` directly — it's a single mutable
`title: String?` property.

The bundled `:recording:runScreenCaptureKitSmoke` Gradle task does this against a tiny Swing
JFrame and dumps a Robot screenshot alongside for cross-checking.

### Implementation note: retain `SCStreamOutput` strongly

`SCStream.addStreamOutput` reads as if it strongly retains the output for the stream's
lifetime, but in practice (macOS 26 / SCK as of this writing) the only delivered frame in
repeated runs was the very first sample buffer if the output was held by a local variable
that went out of scope after `startCapture`. The helper now retains its `FrameOutput` (and
`SCStreamDelegate`) on the recorder instance — this is the difference between "one frame for
the whole recording" and "frames at the configured rate". Keep it that way.

## Permissions

The JVM running Spectre needs two macOS permissions for recording to work:
- **Screen Recording** — for ffmpeg's avfoundation capture, for `ScreenCaptureKitRecorder`'s
  helper subprocess, and for AWT `Robot` screenshots.
- **Accessibility** — for AWT `Robot` mouse / keyboard control.

Grant them under **System Settings → Privacy & Security**, targeting whichever process is
hosting Spectre (your IDE, your test runner, or a standalone `java`). Restart the JVM after
granting — macOS does not refresh TCC entitlements live. `MacOsRecordingPermissions.diagnose()`
returns a human-readable summary you can dump at startup to surface this to developers.

The SCK helper is a child process, so it inherits the host JVM's TCC grant — no separate
permission grant needed for it.

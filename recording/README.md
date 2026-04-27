# Recording

Phase 1 screen recording for Spectre scenarios.

## Public surface

- `Recorder` — interface; produces a `RecordingHandle` for an in-progress capture.
- `FfmpegRecorder` — v1 default. Shells out to a system `ffmpeg` and captures the requested
  region via the macOS avfoundation device. Resolves the binary via PATH (`resolveFfmpegPath()`)
  by default; pass `ffmpegPath` to override (testing, non-PATH installs).
- `RecordingHandle` — `AutoCloseable`. Stop sends `q` on stdin (ffmpeg's documented clean-shutdown
  signal), waits up to 5 s for a graceful exit, then SIGTERM / SIGKILL fallback.
- `RecordingOptions` — frame rate, cursor capture, codec.
- `MacOsRecordingPermissions.diagnose()` — best-effort startup diagnostic for Screen Recording
  and Accessibility permissions; full native detection (`CGPreflightScreenCaptureAccess`,
  `AXIsProcessTrusted`) is deferred to v2 alongside the ScreenCaptureKit work in #18.

## v1 scope

| Capability | Status |
|---|---|
| macOS region capture (avfoundation) | ✅ this module |
| Embedded `ComposePanel` (`windowHandle == 0L`) | ✅ falls through to region capture |
| ScreenCaptureKit window-targeted capture | ⏸ v2 (#18) |
| Windows `gdigrab` recording | ⏸ v3 (#22) |
| Linux `x11grab` / pipewire recording | ⏸ v4 (#27) |

## Permissions

The JVM running Spectre needs two macOS permissions for recording to work:
- **Screen Recording** — for ffmpeg's avfoundation capture and for AWT `Robot` screenshots.
- **Accessibility** — for AWT `Robot` mouse / keyboard control.

Grant them under **System Settings → Privacy & Security**, targeting whichever process is hosting
Spectre (your IDE, your test runner, or a standalone `java`). Restart the JVM after granting —
macOS does not refresh TCC entitlements live. `MacOsRecordingPermissions.diagnose()` returns a
human-readable summary you can dump at startup to surface this to developers.

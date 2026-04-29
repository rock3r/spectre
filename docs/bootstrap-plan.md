# Bootstrap plan

This document used to describe the empty-scaffold state of the repository before any feature
work landed. v1 and v2 have shipped, so it's been retitled and rewritten as a snapshot of
what's done and what's planned. The original spike notes still live at
<https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8>.

## What shipped

### v1 — cross-platform core

- **`core`** — `WindowTracker`, `SemanticsReader`, `ComposeAutomator.inProcess(...)`,
  owner-scoped node identity, selector/query API, `RobotDriver` (real OS input) +
  `RobotDriver.headless()` for in-process semantics-only flows, coordinate mapping,
  `waitForVisualIdle` synchronization.
- **`testing`** — `ComposeAutomatorExtension` (JUnit 5) and `ComposeAutomatorRule` (JUnit 4)
  with pluggable `AutomatorFactory` so headless tests can substitute fakes.
- **`sample-desktop`** — manual smoke surface with counter / popup / scrolling scenarios that
  exercise popup discovery, focus tracking, and coordinate mapping.
- **`sample-intellij-plugin`** — IntelliJ plugin that hosts a Jewel tool window and proves the
  in-process automator reaches IDE-hosted Compose surfaces (`#13` checklist). Includes
  `RunSpectreAction` for the Tools menu and a `-Dspectre.autorun=true` startup activity for
  non-interactive smokes.

### v1 — recording (region capture)

- **`recording.FfmpegRecorder`** — `ffmpeg` + `avfoundation` region capture on macOS. See
  [`docs/RECORDING-LIMITATIONS.md`](RECORDING-LIMITATIONS.md) for the trade-offs.

### v2 — macOS window-targeted recording

- **`recording.screencapturekit.ScreenCaptureKitRecorder`** — window-targeted capture via a
  bundled Swift helper (`recording/native/macos/`). Removes the "anything overlapping the
  region appears in the recording" failure mode for top-level Compose windows.
- **`recording.AutoRecorder`** — picks SCK or ffmpeg per call based on whether the target has
  a usable window handle.
- **Universal-binary opt-in** — `./gradlew :recording:assembleScreenCaptureKitHelper
  -PuniversalHelper` builds an arm64+x86_64 lipo'd helper using only the Xcode CLT (no full
  Xcode required). Off by default to keep local iteration fast.

### v2 — IDE-hosted automated UI test

- **`:sample-intellij-plugin:uiTest`** — `intellij-ide-starter`-driven test that boots a real
  IntelliJ Ultimate IDE, installs the locally-built plugin, fires `RunSpectreAction` through
  the Driver API, and asserts every tagged Compose node lands in `idea.log`. CI runs it in
  [`.github/workflows/ide-uitest.yml`](../.github/workflows/ide-uitest.yml) when plugin / core /
  recording sources change. (`#42`.)

## What's planned

The open work is platform-specific and tracked as labelled GitHub issues. Pick them up on a
machine with the relevant runtime — the issues reference what blocks them.

- **v3 (Windows)** — `#24` tracks the phase. Sub-issues cover Robot input + focus (`#20`), HiDPI
  coordinate mapping under Windows DPI scaling (`#21`), gdigrab-based recording (`#22`), and
  multi-window/popup validation (`#23`).
- **v4 (Linux)** — `#28` tracks the phase. Sub-issues cover Robot input + focus on X11/Wayland
  (`#25`), HiDPI coordinate mapping (`#26`), and ffmpeg X11/Wayland recording (`#27`).
- **Notarization** — `#49` covers signing + notarising the SCK helper for distribution. v2
  intentionally landed unsigned (the helper runs from inside the JVM's process, so end users
  never see a Gatekeeper prompt for it directly), but distribution scenarios may want it.

## Module map

```text
core/                    — automation primitives (semantics, selectors, Robot input, coords)
server/                  — opt-in transport layer (scaffolded, not yet wired)
recording/               — region + window-targeted recording, native helper boundary
testing/                 — JUnit 5 extension / JUnit 4 rule and AutomatorFactory seam
sample-desktop/          — Compose Desktop manual smoke surface
sample-intellij-plugin/  — Jewel-on-IntelliJ tool window + RunSpectreAction
```

For invariants and dependency direction see [`ARCHITECTURE.md`](ARCHITECTURE.md).

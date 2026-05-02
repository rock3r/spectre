# Bootstrap plan

This document used to describe the empty-scaffold state of the repository before any feature
work landed. v1, v2, and v3 have shipped, so it's been retitled and rewritten as a snapshot of
what's done and what's planned. The original spike notes still live at
<https://gist.github.com/rock3r/8e520bb3fe8fe5886367d5e22cefbab8>.

## What shipped

### v1 — cross-platform core

- **`core`** — `WindowTracker`, `SemanticsReader`, `ComposeAutomator.inProcess(...)`,
  owner-scoped node identity, selector/query API, `RobotDriver` (real OS input) +
  `RobotDriver.headless()` for in-process semantics-only flows, coordinate mapping,
  `waitForVisualIdle` synchronization.
- **`server`** — embedded HTTP transport via Ktor (`SpectreServer`) and matching
  `HttpComposeAutomator` client, with serialization DTOs and round-trip integration tests
  (`#9`). Cross-JVM access is wired end-to-end; consumers bring their own server engine.
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
  IntelliJ IDEA, installs the locally-built plugin, fires `RunSpectreAction` through the
  Driver API, and asserts every tagged Compose node lands in `idea.log`. CI runs it in
  [`.github/workflows/ide-uitest.yml`](../.github/workflows/ide-uitest.yml) when plugin / core /
  recording sources change. (`#42`.)

### v3 — Windows platform support

- **`recording.FfmpegBackend` + `gdigrab` argv builders** — OS-aware backend selection so
  `FfmpegRecorder` builds gdigrab argv on Windows instead of avfoundation. Title-based and
  region-based gdigrab are exposed in `FfmpegCli`; backend resolution is deferred to first
  `start()` call so construction is OS-agnostic. (`#22`.)
- **HiDpiMapper Windows validation** — empirically confirmed across 100/125/150/200% scaling
  including the mixed-DPI multi-monitor case. The macOS Retina formula generalises with no
  Windows-specific branch needed. (`#21`.)
- **RobotDriver Windows validation** — `:sample-desktop:runWindowsRobotSmoke` drives a
  Compose window through real `java.awt.Robot` (counter clicks, clipboard `typeText`,
  `clearAndTypeText`, Ctrl+S shortcut); a companion
  `:sample-desktop:runWindowsRobotUnfocusedSmoke` covers the deliberately-unfocused path.
  Production `RobotDriver` needed no Windows-specific changes. (`#20`, `#59`.)
- **Popup-layer discovery on Windows** — `:sample-desktop:validationTestPopupLayers` passes
  for `OnSameCanvas` and `OnComponent` on Windows; `OnWindow` is `assumeFalse`-skipped due to
  a JBR/skiko native crash, tracked separately as `#56`. (`#23`.)

### v3 — CI matrix expansion

- **`.github/workflows/windows.yml`** — runs full `:check` on `windows-latest` with broad
  path filters, mirroring the shape of `ci.yml` (ubuntu).
- **`.github/workflows/macos-check.yml`** — runs full `:check` on `macos-latest`, parity
  with the Windows job. The pre-existing `macos.yml` stays focused on
  `:recording:check` + the Swift helper build.

### v4 — Linux Xorg support + Wayland portal handshake

Validated on a Hyper-V Ubuntu 22.04 dev VM (2026-05-01). Most of v4's "Linux" surface area
turned out to already work without changes; the recording backend needed real implementation
work, and the Wayland recording path turned out to be a two-stage problem.

That single VM is the entirety of v4's Linux validation footprint — same caveat as the
README: Linux is best-effort, broader distro / compositor / WM coverage is welcome
contributions territory.

- **`recording.FfmpegBackend.LinuxX11Grab` + `x11grabRegionCapture` argv builder** — Xorg
  region capture via `ffmpeg -f x11grab`. Mirrors the gdigrab pattern: input-side region
  selection (offset baked into the input URL as `<display>+x,y`, dimensions via
  `-video_size`), no crop filter, no silent-clamp pitfall. (`#75`.)
- **Wayland session detection** — `LinuxX11Grab.checkNotWayland` throws explicitly on
  Wayland sessions (env signals: `XDG_SESSION_TYPE=wayland`, non-blank `WAYLAND_DISPLAY`,
  or a `wayland-*` socket in `XDG_RUNTIME_DIR`). Without this guard, x11grab through
  XWayland succeeds without erroring but produces uniform-black frames — Wayland's
  security model blocks framebuffer reads by clients other than the compositor. (`#77`
  stage 1.)
- **Wayland portal handshake** — `dev.sebastiano.spectre.recording.portal.ScreenCastPortal`
  is a dbus-java client for `org.freedesktop.portal.ScreenCast`. The three-call flow
  (`CreateSession` → `SelectSources` → `Start`) round-trips cleanly on GNOME mutter and the
  recorder extracts a PipeWire stream node id from the `Start.Response`. (`#77` stage 2.)
- **Robot input + popup discovery + HiDPI + multi-window** — already worked on Linux
  Xorg out of the box. `:sample-desktop:validationTest*` was 15/15 + 3/3 popup-layer
  variants on the dev VM with no source changes.

## What's planned

The open work is platform-specific and tracked as labelled GitHub issues. Pick them up on a
machine with the relevant runtime — the issues reference what blocks them.

- **Linux Wayland — stage 3: encoder spawn with FD inheritance** ([`#80`](https://github.com/rock3r/spectre/issues/80)).
  The portal grant is FD-scoped: pipewiresrc only reads the granted node when given the file
  descriptor returned by `OpenPipeWireRemote`. JDK 21's `ProcessBuilder` doesn't inherit
  arbitrary FDs across exec — only stdin/stdout/stderr — so we need to clear `O_CLOEXEC` on
  the FD via JNR-POSIX, then spawn `gst-launch-1.0 ... pipewiresrc fd=$N path=$nodeId ...`
  with the FD inherited. The architecture (`WaylandPortalRecorder`, `GstCli`, `AutoRecorder`
  routing) is already in place from stage 2; stage 3 is the FD-passing plumbing plus the
  encoder lifecycle. Stage 2's `WaylandPortalRecorder.start` throws an explicit
  `UnsupportedOperationException` rather than producing a 0-byte mp4 — so users on Wayland
  see a useful error, not silent corruption.
- **Notarization** — `#49` covers signing + notarising the SCK helper for distribution. v2
  intentionally landed unsigned (the helper runs from inside the JVM's process, so end users
  never see a Gatekeeper prompt for it directly), but distribution scenarios may want it.
- **v3 follow-ups** — `#56` (JBR/skiko OnWindow popup crash tracking). `#55` / `#57` /
  `#58` shipped during the v3 cleanup pass. The backlog-only `#61` (audio capture) sits
  in the same problem space but with no scheduled work.

## Module map

```text
core/                    — automation primitives (semantics, selectors, Robot input, coords)
server/                  — Ktor-based HTTP transport + remote client (#9, shipped in v1)
recording/               — region + window-targeted recording, native helper boundary
testing/                 — JUnit 5 extension / JUnit 4 rule and AutomatorFactory seam
sample-desktop/          — Compose Desktop manual smoke surface
sample-intellij-plugin/  — Jewel-on-IntelliJ tool window + RunSpectreAction
```

For invariants and dependency direction see [`ARCHITECTURE.md`](ARCHITECTURE.md).

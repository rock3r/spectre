# Cursor instructions

This file is **Cursor-only**. Do not copy these notes into `AGENTS.md`, `CLAUDE.md`,
or other shared agent docs. Shared operating rules stay in `AGENTS.md`.

## GitHub

Always use the `gh` CLI for GitHub interactions (PRs, issues, comments, reviews, CI,
labels, checks, merges). An authenticated token is already in the environment.

Do **not** use built-in PR/issue tools (`ManagePullRequest`, GitHub MCP write APIs, or
similar). Prefer `gh` even when another tool is offered.

Typical commands:

```bash
gh pr view
gh pr create
gh pr edit
gh pr comment
gh run list
gh run view
```

## Cursor Cloud specific instructions

The Cloud Agent VM is headless Linux (Ubuntu 24.04, x86_64, no GPU) with JDK 21 (Temurin-shaped
OpenJDK 21, matching per-PR CI) and Rust/cargo already on `PATH`. The Gradle wrapper (9.4.0) and
all standard commands in `AGENTS.md` `## Build & Run` / `docs/STATIC-ANALYSIS.md` work as documented.

- `./gradlew check` (the CI gate) runs fully headless and is green in this environment. On Linux it
  compiles the `spectre-wayland-helper` Rust crate as part of `:recording:processResources`, which
  links `libdbus-1`; `libdbus-1-dev`, `pkg-config`, and `gstreamer1.0-{tools,plugins-base,plugins-good}`
  are baked into the VM image. Do not add these to the update script.
- `AgentAttachIntegrationTest` (in `:check`) assumption-skips on headless JVMs, so a green `:check`
  does not prove the agent attach round-trip. To actually execute it, run it under a display (see
  below), exactly as `.github/workflows/validation-linux.yml` does.
- Two displays exist: `DISPLAY=:1` is the real desktop the `computerUse` tooling drives, and
  `xvfb-run` is available for headless GUI runs. Run the sample app visually with
  `DISPLAY=:1 ./gradlew :sample-desktop:run`; run GUI-dependent tests/smokes headlessly with
  `xvfb-run -a ./gradlew ...` (e.g. `:sample-desktop:validationTest`, `:sample-desktop:runLinuxRobotSmoke`,
  `:agent:test --tests "*AgentAttachIntegrationTest*"`).
- No GPU: Skiko logs `Cannot create Linux GL context` / `Fallback to next API` and then renders in
  software. The window still paints correctly, so treat that log line as benign here.
- No `imagemagick`/`scrot` is installed. To grab a desktop frame use GStreamer, e.g.
  `DISPLAY=:1 gst-launch-1.0 -q ximagesrc num-buffers=1 ! videoconvert ! pngenc ! filesink location=/tmp/frame.png`.

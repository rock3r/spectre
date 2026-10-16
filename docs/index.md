---
title: Spectre
hide:
  - navigation
---

<p align="center">
  <img src="assets/spectre-logo.png" alt="Spectre" width="180" />
</p>

# Spectre

Spectre lets Kotlin tests control a running Compose Desktop application.
It reads the Compose semantics tree and sends mouse and keyboard events.
The default driver sends synthetic AWT events into the window hierarchy.
Tests can select real operating system input when they need it.
Spectre also records the screen in standalone apps and IntelliJ or Jewel hosts.

A test finds a node, performs an action, and checks the result.

!!! warning "Pre-1.0"
    Spectre is pre-1.0. The [stability policy](STABILITY.md) covers stable APIs.
    Experimental HTTP, agent attach, and desktop input coordination APIs may change between releases.
    Read the [security notes](SECURITY.md) before enabling cross-JVM control or recording
    where the application handles untrusted input.

## Why Spectre

- **Inspect running windows.** Spectre reads live semantics, layout, focus, popups,
  and HiDPI coordinates.
- **Choose a process boundary.** `ComposeAutomator.inProcess()` runs with the application.
  The `server` module controls a UI in another JVM, such as an IDE under test.
- **Use synthetic input by default.** [`ComposeAutomator.inProcess()`](guide/interactions.md)
  uses `RobotDriver.synthetic()` to send AWT events into the window hierarchy.
  Pass `RobotDriver()` when a test needs real cursor motion, global focus, or system shortcuts.
- **Coordinate shared input.** Participating real-input test JVMs can serialise focus,
  pointer, keyboard, and clipboard operations. Query and synthetic tests can still run in parallel.
  See [desktop input coordination](guide/input-coordination.md).
- **Capture video and still images.** Spectre supports region capture and platform-specific
  window capture. macOS uses ScreenCaptureKit; Windows uses Windows Graphics Capture.
  Linux uses helpers for Xorg/Xvfb and portal/PipeWire capture.
  [`AutoRecorder` and `AutoScreenshotter`](guide/recording.md) select a backend for each call.
- **Run JUnit tests.** The JUnit 5 extension and JUnit 4 rule create an automator for each test.
  They can save a failure screenshot and semantics tree or launch and attach to an application.
- **Inspect an app interactively.** The `spectre` CLI and MCP tools work without a JUnit test.
  Agent attach also works from another JVM. It prefers preinstalled `spectre-core` and can inject
  core when the target does not include it.

## Where to start

<div class="grid cards" markdown>

- :material-rocket-launch: **[Getting started](guide/getting-started.md)** — Install
  Spectre and write a first test.
- :material-book-open-page-variant: **[The automator](guide/automator.md)** — Concepts:
  semantics surfaces, queries vs. interactions, why there is no auto-wait.
- :material-target: **[Finding nodes](guide/selectors.md)** — `findByTestTag`, `findByText`,
  `hasTag`/`hasText`, `findByContentDescription`, `findByRole`, and the `printTree()`
  debugger.
- :material-clock-fast: **[Synchronization](guide/synchronization.md)**
  — `waitForIdle`, `waitForVisualIdle`, `waitForNode`, and the EDT rule.
- :material-monitor-dashboard: **[Running on CI](guide/ci.md)** — `xvfb`, required test-JVM
  flags, macOS helper mode, and recording test tags.
- :material-lock-clock: **[Experimental input coordination](guide/input-coordination.md)** —
  FIFO leases, JUnit isolation, diagnostics, and explicit forced recovery.
- :material-video: **[Recording and screenshots](guide/recording.md)** — Region,
  window-targeted video, and native still-window screenshots across macOS, Windows,
  and Linux.
- :material-camera: **[Atomic capture](guide/capture.md)** — Window PNG + versioned
  `capture.json` for agents, CLI, and JUnit failure artefacts.
- :material-console: **[CLI](guide/cli.md)** — Attach, inspect, click, capture, and record
  from the `spectre` command (and MCP).
- :material-link-variant: **[Agent attach](guide/agent.md)** — Drive a running Compose JVM
  from another process (experimental; inject when core is not preinstalled).
- :material-refresh: **[Compose Hot Reload](guide/hot-reload.md)** — Optional
  `waitForReloadSettled` when the target runs under Hot Reload (CLI/MCP).
- :material-server: **[Cross-JVM](guide/cross-jvm.md)** — Drive a UI hosted in another JVM
  process via the embedded HTTP transport (experimental; see [Security notes](SECURITY.md)).

</div>

## Project links

- [GitHub repository](https://github.com/rock3r/spectre)
- [Issue tracker](https://github.com/rock3r/spectre/issues)

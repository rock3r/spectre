---
title: Spectre
hide:
  - navigation
---

<p align="center">
  <img src="assets/spectre-logo.png" alt="Spectre" width="180" />
</p>

# Spectre

Spectre is a Kotlin library for **driving live Compose Desktop UIs from automated tests**.
It reads the Compose semantics tree, drives mouse and keyboard input either through real
OS-level events or through synthetic AWT events dispatched straight into the window
hierarchy, and records what happens on screen — against IDE-hosted Compose surfaces
(IntelliJ, Jewel) and standalone desktop apps alike.

If you've used UI Automator on Android or Espresso for that matter, the shape will feel
familiar — Spectre brings the same "find a node, do a thing, assert" loop to Compose Desktop.

!!! warning "Pre-1.0"
    Spectre is pre-1.0. Stable APIs are covered by the project's compatibility policy;
    experimental APIs, including HTTP, agent attach, and desktop input coordination, may change
    between releases. See
    [Stability policy](STABILITY.md) and [Security notes](SECURITY.md) before depending on
    cross-JVM control or recording in environments that handle untrusted input.

## Why Spectre

- **Real Compose Desktop, not a simulator.** Spectre runs against your actual application
  windows. Semantics, layout, focus, popups, and HiDPI all come from the running UI rather
  than a parallel test harness.
- **In-process or cross-JVM.** Use `ComposeAutomator.inProcess()` for the simple case, or
  use the `server` module to drive a UI hosted in a different JVM (e.g., an IDE under test).
- **Real or synthetic input.** [`ComposeAutomator.inProcess()`](guide/interactions.md)
  defaults to OS-level `java.awt.Robot` events. Swap in `RobotDriver.synthetic(...)` and
  AWT events go directly into the window hierarchy — useful when tests run in parallel
  and can't fight over OS focus.
- **Experimental cooperative input leases.** Participating real-input test JVMs can serialise
  focus, pointer, keyboard, and clipboard work without disabling parallel query/synthetic tests.
  See [desktop input coordination](guide/input-coordination.md).
- **Recording and screenshots built in.** Region capture, plus window-targeted recording
  and still screenshots where the platform exposes them (ScreenCaptureKit on macOS,
  Windows Graphics Capture on Windows, helper-driven Xorg/Xvfb and portal/PipeWire
  capture on Linux). The
  [`AutoRecorder` and `AutoScreenshotter`](guide/recording.md) pick the right backend.
- **JUnit-friendly.** Drop-in extension and rule for JUnit 5 and JUnit 4 manage a per-test
  automator instance for you — including failure artifacts (screenshot + semantics tree)
  and optional launch-and-attach.
- **CLI and agent attach.** Drive or inspect a live app without writing a test first via the
  `spectre` CLI / MCP, or attach from another JVM with the experimental agent transport
  (preinstalled `spectre-core` preferred; inject when the target has no core).

## Where to start

<div class="grid cards" markdown>

- :material-rocket-launch: **[Getting started](guide/getting-started.md)** — Install
  Spectre and write your first test.
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
  `capture.json` for agents, CLI, and JUnit failure artifacts.
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

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

!!! warning "Pre-release"
    Spectre is in bootstrap. There are no published Maven artifacts yet, the public API is
    still settling, and parts of the codebase are explicitly flagged as not yet hand-audited
    in the [README](https://github.com/rock3r/spectre#readme). Treat anything here as a
    moving target until a tagged release lands.

## Why Spectre

- **Real Compose Desktop, not a simulator.** Spectre runs against your actual application
  windows. Semantics, layout, focus, popups, and HiDPI all come from the running UI rather
  than a parallel test harness.
- **In-process or cross-JVM.** Use `ComposeAutomator.inProcess()` for the simple case, or
  use the `server` module to drive a UI hosted in a different JVM (e.g. an IDE under test).
- **Real or synthetic input.** [`RobotDriver`](guide/interactions.md) defaults to OS-level
  `java.awt.Robot` events. `RobotDriver.synthetic(...)` dispatches AWT events directly into
  the window hierarchy — useful when tests run in parallel and can't fight over OS focus.
- **Recording built in.** Region capture via `ffmpeg`, plus window-targeted capture
  (ScreenCaptureKit on macOS, `gdigrab` on Windows, x11grab/portal on Linux). The
  [`AutoRecorder`](guide/recording.md) picks the right backend per call.
- **JUnit-friendly.** Drop-in extension and rule for JUnit 5 and JUnit 4 manage a per-test
  automator instance for you.

## Where to start

<div class="grid cards" markdown>

- :material-rocket-launch: **[Getting started](guide/getting-started.md)** — Install
  Spectre and write your first test.
- :material-book-open-page-variant: **[The automator](guide/automator.md)** — Concepts:
  semantics surfaces, queries vs. interactions, why there is no auto-wait.
- :material-target: **[Finding nodes](guide/selectors.md)** — `findByTestTag`, `findByText`,
  `findByContentDescription`, `findByRole`, and the `printTree()` debugger.
- :material-clock-fast: **[Synchronization](guide/synchronization.md)** —
  `waitForIdle`, `waitForVisualIdle`, `waitForNode`, and the EDT rule.
- :material-video: **[Recording](guide/recording.md)** — Region and window-targeted capture
  across macOS, Windows, and Linux.
- :material-server: **[Cross-JVM](guide/cross-jvm.md)** — Drive a UI hosted in another JVM
  process via the embedded HTTP transport.

</div>

## Project links

- [GitHub repository](https://github.com/rock3r/spectre)
- [Issue tracker](https://github.com/rock3r/spectre/issues)

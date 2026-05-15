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
    experimental APIs, especially the HTTP transport, may change between releases. See
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
- :material-clock-fast: **[Synchronization](guide/synchronization.md)**
  — `waitForIdle`, `waitForVisualIdle`, `waitForNode`, and the EDT rule.
- :material-video: **[Recording](guide/recording.md)** — Region and window-targeted capture
  across macOS, Windows, and Linux.
- :material-server: **[Cross-JVM](guide/cross-jvm.md)** — Drive a UI hosted in another JVM
  process via the embedded HTTP transport (experimental; see [Security notes](SECURITY.md)).

</div>

## Project links

- [GitHub repository](https://github.com/rock3r/spectre)
- [Issue tracker](https://github.com/rock3r/spectre/issues)

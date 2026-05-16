# Guide

The guide walks through using Spectre end-to-end:

1. **[Installation](installation.md)** — how to consume Spectre while it's pre-release.
2. **[Getting started](getting-started.md)** — write your first test against a Compose
   Desktop window.
3. **[The automator](automator.md)** — the mental model: surfaces, the semantics tree,
   queries vs. interactions, and why there's no auto-wait.
4. **[Finding nodes](selectors.md)** — selector reference.
5. **[Driving input](interactions.md)** — clicks, swipes, scrolling, typing, screenshots.
6. **[Synchronization](synchronization.md)** — wait helpers and the EDT rule.
7. **[JUnit integration](junit.md)** — `ComposeAutomatorExtension` (JUnit 5) and
   `ComposeAutomatorRule` (JUnit 4).
8. **[Running on CI](ci.md)** — JVM flags, `xvfb`, macOS helper JVMs, and recording tags.
9. **[Recording](recording.md)** — region and window-targeted screen capture.
10. **[Cross-JVM access](cross-jvm.md)** — driving a UI hosted in another JVM.
11. **[IntelliJ-hosted Compose](intellij.md)** — Jewel-on-IntelliJ tool windows.
12. **[Troubleshooting](troubleshooting.md)** — platform-specific gotchas.

If you're new to Spectre, start with [Installation](installation.md) and
[Getting started](getting-started.md), then read [The automator](automator.md) before
dipping into the per-topic pages.

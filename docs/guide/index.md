# Guide

The guide walks through using Spectre end-to-end:

1. **[Installation](installation.md)** — how to consume Spectre while it's pre-release.
2. **[Getting started](getting-started.md)** — write your first test against a Compose
   Desktop window.
3. **[The automator](automator.md)** — the mental model: surfaces, the semantics tree,
   queries vs. interactions, and why there's no auto-wait.
4. **[Finding nodes](selectors.md)** — selector reference.
5. **[Driving input](interactions.md)** — clicks, swipes, scrolling, typing, screenshots.
6. **[Experimental input coordination](input-coordination.md)** — serialize real input across
   participating JVMs, including JUnit and forced recovery.
7. **[Synchronization](synchronization.md)** — wait helpers and the EDT rule.
8. **[JUnit integration](junit.md)** — `ComposeAutomatorExtension` (JUnit 5) and
   `ComposeAutomatorRule` (JUnit 4).
9. **[Running on CI](ci.md)** — JVM flags, `xvfb`, macOS helper JVMs, and recording tags.
10. **[Recording and screenshots](recording.md)** — region capture, window-targeted video,
    and native still-window screenshots.
11. **[Cross-JVM access](cross-jvm.md)** — driving a UI hosted in another JVM.
12. **[Agent attach](agent.md)** — attach to a running Compose JVM (preinstalled core or inject).
13. **[Capability matrix](capability-matrix.md)** — ops × transports × platforms, with
    fail-closed CI evidence for every Supported cell.
14. **[CLI](cli.md)** — interactive `spectre` command and MCP server.
15. **[Atomic capture](capture.md)** — window PNG + versioned tree for agents.
16. **[Compose Hot Reload](hot-reload.md)** — optional reload settle wait and key
    invalidation when the target runs under HR (CLI/MCP only).
17. **[IntelliJ-hosted Compose](intellij.md)** — Jewel-on-IntelliJ tool windows.
18. **[Troubleshooting](troubleshooting.md)** — platform-specific gotchas.

If you're new to Spectre, start with [Installation](installation.md) and
[Getting started](getting-started.md), then read [The automator](automator.md) before
dipping into the per-topic pages.

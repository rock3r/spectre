# Atomic capture

Atomic capture freezes one Compose window into a PNG plus a versioned semantics tree
(`capture.json`) taken under the same EDT/intent tick. Agents and scripts get only a
decision-grade summary back; the full tree stays on disk for `jq` and other tools.

Do not confuse this with CLI/MCP **fullscreen screenshot** (full virtual desktop PNG, no
tree) or **primary-display / window recording** (MP4 paths). Mode comparison:
[CLI — Capture and recording](cli.md#capture-and-recording).

## CLI

```shell
spectre capture <session-id> [--window N] [--out-dir DIR] [--json]
spectre captures list [--all] [--json]
spectre captures prune --keep 20
spectre captures prune --session <session-id>
```

Default layout:

```text
$TMPDIR/spectre/captures/NNNN-<timestamp>/
  capture.json
  screenshot.png
```

See [CLI](cli.md) for full flags, retention, and detach leftover reports.

## Agent skill: `spectre-capture`

Ship path in this repository: [`skills/spectre-capture/SKILL.md`](https://github.com/rock3r/spectre/blob/main/skills/spectre-capture/SKILL.md).

Install or copy that skill into the locations your agent harness already loads (for example
project `skills/`, Claude skill dirs, or the packaged `skill/` tree for the general automation
skill). Capture summaries and detach reports name **`spectre-capture`** so agents can discover
it in-band.

The skill covers:

- schemaVersion **1** field reference
- ready-made `jq` recipes (clickable nodes by text, bounds by test tag, tree diffs)
- capture → act → capture → diff workflow
- node-key lifetime (re-capture after navigation / re-attach / hot reload settle)
- division of labor when both Compose Hot Reload MCP and Spectre are configured
- `spectre captures prune` cleanup guidance
- a manual find-click-verify recipe against a live fixture

For reload settle and generation-stamped keys, see
[Compose Hot Reload awareness](hot-reload.md).

## Schema versioning

`capture.json` is stable API. When `schemaVersion` bumps:

1. Update golden fixtures under `core/src/test/resources/capture/`.
2. Bump the **`spectre-capture`** skill (content + `package.json` version).
3. Update this page and [CLI](cli.md) examples.
4. Note the skill bump on the release checklist in [PUBLISHING.md](https://github.com/rock3r/spectre/blob/main/docs/PUBLISHING.md).

## In-process API

```kotlin
automator.waitForVisualIdle() // settle first when pixels matter
val result = automator.capture(windowIndex = 0)
// result.captureJson + result.pngBytes; write via your own paths or the agent/CLI surfaces
```

With `spectre-recording` present, the PNG is a **window-scoped** native still (same path as
`screenshot(windowIndex)`), not a silent desktop crop after a failed native still. Without
recording on the classpath, the still is a Robot region capture of the Compose surface (agent
inject payloads often omit recording). Settle the UI before calling so the semantics tree and
pixels stay decision-grade across native still-helper latency.

Library details live in `:core` under `dev.sebastiano.spectre.core.capture`.

## Failure artifacts from JUnit

On a **failed** Spectre JUnit test, `ComposeAutomatorExtension` / `ComposeAutomatorRule`
write the same `capture.json` + `screenshot.png` layout under `build/reports/spectre/`
(not `$TMPDIR`). See [JUnit integration — Failure artifacts](junit.md#failure-artifacts)
and the CI upload snippet in [Running on CI](ci.md#failure-artifacts).

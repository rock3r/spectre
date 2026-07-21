# Atomic capture

Atomic capture freezes one Compose window into a PNG plus a versioned semantics tree
(`capture.json`) taken under the same EDT/intent tick. Agents and scripts get only a
decision-grade summary back; the full tree stays on disk for `jq` and other tools.

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
- node-key lifetime (re-capture after navigation / re-attach)
- `spectre captures prune` cleanup guidance
- a manual find-click-verify recipe against a live fixture

## Schema versioning

`capture.json` is stable API. When `schemaVersion` bumps:

1. Update golden fixtures under `core/src/test/resources/capture/`.
2. Bump the **`spectre-capture`** skill (content + `package.json` version).
3. Update this page and [CLI](cli.md) examples.
4. Note the skill bump on the release checklist in [PUBLISHING.md](https://github.com/rock3r/spectre/blob/main/docs/PUBLISHING.md).

## In-process API

```kotlin
val result = automator.capture(windowIndex = 0)
// result.captureJson + result.pngBytes; write via your own paths or the agent/CLI surfaces
```

Library details live in `:core` under `dev.sebastiano.spectre.core.capture`.

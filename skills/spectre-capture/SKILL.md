---
name: spectre-capture
description: >
  Use when inspecting, diffing, or pruning Spectre atomic captures (`capture.json` + `screenshot.png` from
  `spectre capture`, MCP `capture`, or `ComposeAutomator.capture`). Use `spectre` for general UI automation.
license: Apache-2.0
compatibility: Requires the Spectre CLI (or in-process capture API); describes schemaVersion 2 and compatible schemaVersion 1 reading.
---

# Spectre capture output

Atomic capture writes a native window still (`screenshot.png`) and a versioned semantics snapshot (`capture.json`) to
disk, returning paths, summary counts, and dimensions so callers query `capture.json` with `jq` instead of loading full
trees into context. Run `spectre guide capture` or `spectre guide --search "<words>"` for bundled CLI docs (published at
<https://spectre.sebastiano.dev/guide/capture/>).

Schema version 2 supports two capture sources. CLI `capture --source compose` and MCP `capture`
with `source: "compose"` are the default and write `capture.json`. With `--source accessibility`
(or MCP `source: "accessibility"`), an attached session writes `accessibility.json` plus the same
native screenshot. That native document has `source: "os-accessibility"`, `windows`, native node
`name`/`bounds`, and `notes`; do not apply the Compose recipes below to it. The standalone
`spectre a11y tree` and MCP `accessibility_tree` operations return the same native snapshot shape.

Always branch on `schemaVersion` before reading. Version 1 Compose captures remain readable with
their original fields, but they have no document `source`, `parentKey`, `id`, `platformRole`,
`states`, `value`, `onScreen`, or `boundsSpace`. Treat a missing `source` as legacy Compose only
when `schemaVersion == 1`; never use that fallback for an unknown future version. The existing
`testTag`, text, state, `boundsImage`, and `boundsScreen` recipes below work for both versions when
they do not select a version-2-only field.

When Compose Hot Reload MCP is also configured, use Hot Reload MCP to check recomposition during live reload sessions
and Spectre for semantics inspection, input, and captures (`spectre wait --reload-settled` / MCP
`wait_for_reload_settled` is interactive-only, not in `:testing`; see
<https://spectre.sebastiano.dev/guide/hot-reload/>).

## Storage layout and pruning

Default root (mode `0700`, lazy-capped at the last 50 closed captures; ledger at
`$TMPDIR/spectre/capture-ledger.jsonl`):

```text
$TMPDIR/spectre/captures/NNNN-<timestamp>/
  capture.json
  screenshot.png
```

```shell
spectre captures list [--all] [--json]
spectre captures prune --session <session-id>   # printed on detach; preferred over --all
spectre captures prune --keep 20
spectre captures prune --older-than 7d
```

- Live sessions are never touched without `--force`.
- Captures written via `--out-dir` / MCP `out_dir` are never auto-pruned without `--include-out-dir`.

## `capture.json` schema (`schemaVersion 2`)

A `schemaVersion` bump also requires bumping this skill's version.

```jsonc
{
  "schemaVersion": 2,
  "source": "compose-semantics",
  "capturedAt": "ISO-8601 UTC",
  "window": {
    "index": 0,
    "surfaceId": "…",
    "title": "…",
    "isPopup": false,
    "boundsScreen": { "x": 0, "y": 0, "width": 0, "height": 0 },
    "boundsSpace": "screen",
    "densityScaleX": 1.0,
    "densityScaleY": 1.0,
    "imageWidth": 800,
    "imageHeight": 600
  },
  "nodes": [ /* flat pre-order list; parentKey rebuilds the tree */ ],
  "summary": {
    "nodeCount": 0,
    "taggedNodeCount": 0,
    "textedNodeCount": 0,
    "imageWidth": 800,
    "imageHeight": 600,
    "captureDurationMs": 0
  }
}
```

| Node field | Meaning |
|---|---|
| `key` / `parentKey` | Snapshot-local id/index path and its parent (`null` at a root) |
| `id` | Platform identifier; Compose fills it from `testTag`; nullable and not globally unique |
| `testTag` | Compose `Modifier.testTag` |
| `text` / `texts` | Visible text (`summary.textedNodeCount` counts non-empty `text` **or** `editableText`) |
| `editableText` | Text-field contents when present |
| `contentDescription` / `role` | Accessibility description and semantics role string |
| `platformRole` / `states` / `value` | Raw OS role, normalised state strings, and current value |
| `enabled` / `clickable` / `focused` / `selected` | Boolean state flags |
| `boundsImage` | **Primary** — device-pixel coordinates inside `screenshot.png` |
| `boundsScreen` | Secondary — logical screen coordinates for input targeting |
| `onScreen` | Bounds/window intersection, never a copied platform visibility flag |

`screenshot.png` is in **device pixels** (e.g., 2× `boundsScreen` on Retina), so `window.imageWidth` can exceed
`window.boundsScreen.width`. Use `boundsImage` for PNG inspection and `boundsScreen` for pointer coordinates.

## `jq` recipes

```shell
# Enabled clickable nodes matching text or editableText (case-insensitive)
jq -r --arg q 'save' '
  .nodes[]
  | select(.clickable and .enabled)
  | select((.text // "" | ascii_downcase | contains($q))
        or (.editableText // "" | ascii_downcase | contains($q)))
  | "\(.key)\t\(.testTag // "-")\t\(.text // .editableText // "")"
' "$CAP"

# Resolve snapshot key and image/screen bounds by testTag
KEY=$(jq -r --arg tag 'submit' '.nodes[] | select(.testTag == $tag and .clickable) | .key' "$CAP" | head -n1)
jq -r --arg tag 'submit' '.nodes[] | select(.testTag == $tag) | "\(.key) image=\(.boundsImage) screen=\(.boundsScreen)"' "$CAP"

# Structural diff between before/after captures
diff -u \
  <(jq -r '.nodes[] | "\(.key)\t\(.testTag // "")\t\(.text // .editableText // "")"' "$CAP_BEFORE" | sort) \
  <(jq -r '.nodes[] | "\(.key)\t\(.testTag // "")\t\(.text // .editableText // "")"' "$CAP_AFTER" | sort) || true
```

## Workflow (`capture → act → capture → diff`) and key lifetime

1. **Settle the UI first** (`waitForIdle`/`waitForVisualIdle` or app-level wait); `capture` does not auto-idle. Visual idle requires native window capture unless the caller explicitly opts into `allowScreenRegionFallback = true` and accepts occlusion-sensitive desktop pixels.
2. **Capture before** (`spectre capture <session-id> --json`), locate a target by `id`/`testTag` via `jq`, resolve its
   current actionable key with `spectre find`, and act with that live key. Snapshot keys are structural evidence, not
   durable action handles.
3. **Capture after**, verify the diff, and **prune** (`spectre captures prune --session <session-id>`) when finished.

Node keys belong to exactly **one tree snapshot**:

- Re-capture and re-resolve keys after navigation, recomposition, scrolling, or re-attach.
- **Compose Hot Reload:** start `spectre wait --reload-settled <session-id>` (or MCP `wait_for_reload_settled`) *before*
  triggering reload so it observes the settle chain. After reload settles, Spectre invalidates prior generation-stamped
  keys (`nodeNotFound`). Because `capture.json` keys lack generation stamps for post-reload input, resolve fresh action
  keys via `spectre tree` or `spectre find` after a reload, keeping `capture.json` for visual/tree evidence.

## Entry points and fail-closed still errors

| Surface | Invocation |
|---|---|
| CLI | `spectre capture <session-id> [--window N] [--out-dir DIR] [--json]` |
| MCP | `capture` (`session_id`, optional `window_index`, `out_dir`, `include_image`) |
| Library | `ComposeAutomator.capture(windowIndex)` (with `CaptureArtifactsWriter`) or `AttachedAutomator.capture` |

`screenshot.png` is always a native window still — Spectre never falls back to a desktop crop (for fullscreen over
attach, pass `spectre screenshot <session-id> --fullscreen`). Common failure causes:

- **Blank or duplicate window title / bounds:** `Frame`/`Dialog` lookup matches unique non-blank titles; untitled
  windows (such as `JWindow` popups) match unique position + size.
- **Missing OS permission:** macOS Screen Recording TCC (`spectre permissions check`) or Linux Wayland one-time portal
  grant (`spectre permissions request`). Ask a human at the machine once; never retry in a loop.
- **Linux Wayland non-`Frame`/`Dialog` popup:** unsupported yet (`#640`); capture the owner window instead.
- **Window moved/resized mid-capture:** settle the UI and retry once.
- **In-process/agent `capture` without `:recording`:** `ComposeAutomator.capture` and `AttachedAutomator.capture` run
  inside the target JVM and need `spectre-recording` plus its OS helper; prefer `spectre capture` for attached apps.

---
name: spectre-capture
description: >
  Use when working with Spectre atomic captures — capture.json + screenshot.png written by
  `spectre capture`, the capture MCP tool, or ComposeAutomator.capture. Covers the versioned
  capture schema, jq recipes over the tree, capture → act → capture → diff verification,
  node-key lifetime (including Compose Hot Reload invalidation), division of labor vs the
  Hot Reload MCP, and captures prune cleanup.
license: Apache-2.0
compatibility: Requires the Spectre CLI (or in-process capture API) and schemaVersion 1 captures.
---

# Spectre capture output

## Compose Hot Reload MCP and Spectre

When both MCP servers are available, use each for its supported operations:

> Use Compose Hot Reload MCP to check recomposition during a live Hot Reload session.
> Use Spectre for other inspection and automation tasks.

- **HR MCP** — check recomposition during a live Hot Reload session.
- **Spectre** (CLI / MCP / capture) inspects semantics trees and node keys.
  It sends synthetic or real input, takes screenshots, records video, and
  makes atomic captures. It attaches to Spectre-enabled JVMs. Use
  `wait --reload-settled` or `wait_for_reload_settled` before inspecting after reload.

Reload awareness is optional and applies to interactive development.
The JUnit `:testing` API does not include it.
See the [Hot Reload guide](https://spectre.sebastiano.dev/guide/hot-reload/).

Atomic capture writes window pixels and semantics into two files:

| File | Role |
|---|---|
| `screenshot.png` | Window pixels at capture time (primary visual evidence) |
| `capture.json` | Versioned semantics tree + window metadata + summary |

The caller receives paths, node counts, and image dimensions.
The full tree stays on disk in `capture.json`. Query it with `jq` or another JSON tool.

The skill name is **`spectre-capture`**. Capture summaries and detach reports use that name.

## Where captures live

Default root (mode `0700`):

```text
$TMPDIR/spectre/captures/NNNN-<timestamp>/
  capture.json
  screenshot.png
```

- Sequence numbers are allocated by scanning the root (no shared counter).
- Client `--out-dir` / MCP `out_dir` overrides the root for that capture only.
- Append-only ledger: `$TMPDIR/spectre/capture-ledger.jsonl`.

List / prune:

```shell
spectre captures list [--all] [--json]
spectre captures prune --keep 20
spectre captures prune --session <session-id>
spectre captures prune --older-than 7d
# never touches live sessions without --force
# never auto-touches --out-dir captures without --include-out-dir
```

On detach, Spectre reports that session’s leftover paths and the exact prune command.

## capture.json schema (schemaVersion 1)

The capture schema is versioned. A schema version change also requires a skill version change.

```jsonc
{
  "schemaVersion": 1,
  "capturedAt": "ISO-8601 UTC",
  "window": {
    "index": 0,
    "surfaceId": "…",
    "title": "…",
    "isPopup": false,
    "boundsScreen": { "x": 0, "y": 0, "width": 0, "height": 0 },
    "densityScaleX": 1.0,
    "densityScaleY": 1.0,
    "imageWidth": 800,
    "imageHeight": 600
  },
  "nodes": [ /* flat DFS list; no parent/child edges in v1 */ ],
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

### Node fields

| Field | Meaning |
|---|---|
| `key` | Owner-scoped node key for `click` / friends **on the same attach session** |
| `testTag` | Compose `Modifier.testTag` |
| `text` / `texts` | Visible text |
| `editableText` | Text field contents when present |
| `contentDescription` | A11y description |
| `role` | Semantics role string when present |
| `enabled` / `clickable` / `focused` / `selected` | Flags |
| `boundsImage` | **Primary** — pixels of `screenshot.png` |
| `boundsScreen` | Secondary — screen-space for input targeting |

`screenshot.png` uses **screen pixels**. On a 2× display, its dimensions are twice
the window's dp dimensions. `window.imageWidth` can differ from `window.boundsScreen.width`.
Use `boundsImage` for PNG coordinates and `boundsScreen` for input coordinates.
`window.densityScaleX` and `densityScaleY` help compare captures from different display scales.

`summary.textedNodeCount` counts nodes with non-empty `text` **or** `editableText`.

## jq recipes

Assume `CAP=…/capture.json`.

```shell
# All clickable nodes with text matching a substring (case-insensitive)
jq -r --arg q 'save' '
  .nodes[]
  | select(.clickable and .enabled)
  | select((.text // "" | ascii_downcase | contains($q))
        or (.editableText // "" | ascii_downcase | contains($q)))
  | "\(.key)\t\(.testTag // "-")\t\(.text // .editableText // "")"
' "$CAP"

# Bounds of a test tag (image pixels)
jq -r --arg tag 'submit' '
  .nodes[] | select(.testTag == $tag)
  | "\(.key) image=\(.boundsImage) screen=\(.boundsScreen)"
' "$CAP"

# Node key for click after finding by tag
KEY=$(jq -r --arg tag 'submit' '
  .nodes[] | select(.testTag == $tag and .clickable) | .key
' "$CAP" | head -n1)

# Diff two captures' node keys + tags + text (structural smoke)
jq -r '.nodes[] | "\(.key)\t\(.testTag // "")\t\(.text // .editableText // "")"' "$CAP_BEFORE" \
  | sort > /tmp/before.txt
jq -r '.nodes[] | "\(.key)\t\(.testTag // "")\t\(.text // .editableText // "")"' "$CAP_AFTER" \
  | sort > /tmp/after.txt
diff -u /tmp/before.txt /tmp/after.txt || true

# Summary only
jq '.summary' "$CAP"
```

## Workflow: capture → act → capture → diff

1. **Settle the UI** yourself (`waitForIdle` / `waitForVisualIdle` in-process, or wait in the
   target app). Capture does **not** auto-idle.
2. **Capture before**:
   ```shell
   spectre capture <session-id> --json
   # note directory / captureJson from the summary
   ```
3. **Find a target** with `jq` on `capture.json` (prefer `testTag`, then text).
4. **Act** using the node **key** from that same capture while the session stays attached:
   ```shell
   spectre click <session-id> "$KEY"
   ```
5. **Capture after** and **diff** trees (or re-query for the expected text/tag).
6. **Prune** when done:
   ```shell
   spectre captures prune --session <session-id>
   ```

### Node-key lifetime (critical)

- Keys are valid for the **current attach session** and the tree they came from.
- After navigation, re-composition, or re-attach, **re-capture** and re-resolve keys.
- Do not cache keys across detach/attach cycles.
- **Compose Hot Reload:** In reload-aware attaches, `tree` and `find` return
  generation-stamped keys. Spectre clears them after a successful reload settle.
  1. Start `spectre wait --reload-settled <session-id>` or MCP
     `wait_for_reload_settled` before triggering reload. The wait must already
     be active to observe the settle chain.
  2. Trigger reload. After the wait returns, call `tree` or `find` and resolve
     new keys from that response.
  3. Old keys fail as `nodeNotFound`. Do not reuse them.
  4. Use `capture.json` for evidence and `jq`. Its keys lack the stamp needed
     for post-reload clicks. Use `tree` or `find` keys for input.

## Capture from MCP / CLI / library

| Surface | How |
|---|---|
| CLI | `spectre capture <session-id> [--window N] [--out-dir DIR] [--json]` |
| MCP | `capture` tool (`session_id`, optional `window_index`, `out_dir`, `include_image`) |
| In-process | `ComposeAutomator.capture(windowIndex)` → files via a caller-owned writer, or agent `AttachedAutomator.capture` |

Return summary fields first. Read `capture.json` when the full tree is needed.

## Cleanup guidance

- Default root is lazy-capped (keep last 50 **closed** captures). Live sessions are never auto-pruned.
- Explicit `--out-dir` captures are **never** auto-deleted; only `captures prune --include-out-dir`.
- Prefer session-scoped prune from the detach report over blanket `--all`.

## Manual validation recipe

Validate with the skill and Spectre CLI:

1. Start `agent-test-fixture` (or any tagged Compose Desktop fixture).
2. `spectre ps --json` → `spectre attach <pid> --json`.
3. `spectre capture <session-id> --json` → open `capture.json` with `jq`.
4. Resolve a clickable node by `testTag` or text; `spectre click <session-id> <key>`.
5. Capture again; confirm the expected text/tag change with `jq` or `diff`.
6. `spectre detach <session-id>`; run the printed prune command.

## Related

- User guide CLI: <https://spectre.sebastiano.dev/guide/cli/>
- Capture skill page: <https://spectre.sebastiano.dev/guide/capture/>
- Compose Hot Reload awareness: <https://spectre.sebastiano.dev/guide/hot-reload/>
- General UI automation skill: `spectre-ui-automation` / `spectre`

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

## Division of labor (HR MCP vs Spectre)

If this agent also has Compose Hot Reload’s MCP server configured, **do not flip a coin per
call**. Use this rule verbatim:

> **If you have HR available and want quick sanity checks while iterating on a live app, use the
> HR MCP; in any other case, Spectre is the right choice.**

- **HR MCP** — quick reload-native sanity checks while iterating on a live HR run.
- **Spectre** (CLI / MCP / capture) — semantics tree, node keys, real input, screenshots,
  recording, atomic capture, attach to any Spectre-enabled JVM, and
  `wait --reload-settled` / `wait_for_reload_settled` when you need settle + re-inspect.

Reload awareness is **optional** and **dev-loop only**. It is not part of Spectre’s JUnit
`:testing` surface. Full user guide: <https://spectre.sebastiano.dev/guide/hot-reload/>

Atomic capture freezes one Compose window into:

| File | Role |
|---|---|
| `screenshot.png` | Window pixels at capture time (primary visual evidence) |
| `capture.json` | Versioned semantics tree + window metadata + summary |

Only a **decision-grade summary** is returned to agents (paths, node counts, image size). The full
tree stays on disk — query it with `jq` (or any JSON tool). This skill is the map.

Skill name (for discovery): **`spectre-capture`**. Capture summaries and detach leftovers reports
reference this name on purpose.

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

Stable API surface — a schema bump is a skill bump (release checklist).

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
- **Compose Hot Reload:** on reload-aware attaches, keys are generation-stamped and cleared after
  a successful reload settle. After you (or the IDE) hot-reloads code:
  1. `spectre wait --reload-settled <session-id>` (or MCP `wait_for_reload_settled`)
  2. **Re-capture** (or `tree` / `find`) and resolve keys from the **new** tree
  3. Never reuse pre-reload keys — they fail as `nodeNotFound`

## Capture from MCP / CLI / library

| Surface | How |
|---|---|
| CLI | `spectre capture <session-id> [--window N] [--out-dir DIR] [--json]` |
| MCP | `capture` tool (`session_id`, optional `window_index`, `out_dir`, `include_image`) |
| In-process | `ComposeAutomator.capture(windowIndex)` → files via your own writer, or agent `AttachedAutomator.capture` |

Prefer **files over inlining** the tree. Return summary fields to the agent; read `capture.json` on demand.

## Cleanup guidance

- Default root is lazy-capped (keep last 50 **closed** captures). Live sessions are never auto-pruned.
- Explicit `--out-dir` captures are **never** auto-deleted; only `captures prune --include-out-dir`.
- Prefer session-scoped prune from the detach report over blanket `--all`.

## Manual validation recipe

With only this skill + the Spectre CLI (no source reading):

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

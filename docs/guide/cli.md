# CLI

The `spectre` command is for inspecting and driving a running Compose Desktop application
without first writing a JUnit test. It is useful for debugging a live UI, exploring its
semantics tree, capturing evidence while developing, and giving an MCP client access to the
same capabilities.

The command starts a private local daemon when an operation needs one. The daemon keeps attached
sessions available across short-lived CLI invocations and is shared with `spectre mcp`.

## Install

On macOS, install the formula from this repository:

```shell
brew tap rock3r/spectre https://github.com/rock3r/spectre
brew install rock3r/spectre/spectre
```

On Windows, add the Scoop bucket:

```powershell
scoop bucket add spectre https://github.com/rock3r/spectre
scoop install spectre
```

On Linux, download the matching archive from the [GitHub release](https://github.com/rock3r/spectre/releases),
extract it, and put its `spectre` executable on `PATH`.

Run `spectre --help` after installation. Every command also accepts `--help`; use it as the
authoritative reference for command arguments and options when this page and the binary disagree.

## What it can do

The CLI follows a simple loop:

1. Find a running JVM with `ps`, **or** launch one with `spectre launch -- <command>`.
2. Attach it and retain the returned session ID (or hold the launch session open).
3. Inspect windows, the semantics tree, or nodes (test tag, text, waits).
4. Use a current node key to click, type, swipe, scroll, or press keys, then inspect again.
5. Capture a screenshot, atomic capture, or recording when useful.
6. Detach the session (or kill the daemon) when finished.

### Launch and attach

#### End-to-end: existing process

```shell
# 1. Discover attachable JVMs (same OS user; daemon/CLI excluded).
spectre ps --json

# 2. Attach and keep the session id.
SESSION=$(spectre attach <pid> --json | jq -r .id)

# 3. Drive and inspect while the session lives in the shared daemon.
spectre windows "$SESSION"
spectre tree "$SESSION" --json
spectre find "$SESSION" save-button --json
spectre click "$SESSION" <node-key>
spectre type "$SESSION" "A short note"

# 4. Release this session (daemon stays up for other sessions / MCP).
spectre detach "$SESSION"

# Or stop the whole daemon and discard every session:
# spectre daemon kill
```

**Session lifetime (attach path):**

| Action | Effect |
| --- | --- |
| `spectre attach <pid>` | Creates a daemon session; prints session id (JSON: `id`, `pid`). |
| Further CLI / MCP tools | Reuse that id against the **same per-user daemon**. |
| `spectre detach <session-id>` | Ends that session; reports leftover captures and a prune command when any exist. |
| `spectre daemon kill` | Stops the daemon and **discards all sessions**. |
| Leaving the process attached without detach | Session remains until detach, daemon kill, or daemon crash/idle teardown. |

Retain the session id yourself — the CLI does not store a “current session” in the shell.

#### End-to-end: launch harness

`spectre launch` starts a command, runs staged readiness (process alive → JVM attachable →
agent bootstrap → first window), attaches Spectre, and tears the process tree down when the
session ends. Prefer **prod-like** launches (`java -jar`, installDist, packaged apps). Gradle
`./gradlew :app:run` / `hotRun` work but print a loud warning and require `--app-name` when the
app JVM is daemon-spawned.

```shell
# Prod-like (recommended)
spectre launch --once -- java -jar app/build/libs/app.jar

# Gradle-ish (supported with warnings; name filter recommended)
spectre launch --once --app-name ComposeFixtureMain -- ./gradlew :agent-test-fixture:run

# Hold the session open until Enter / EOF, then detach and tear down the process tree
spectre launch -- java -jar app.jar
```

Options:

- `-C` / `--directory` — working directory for the launched process
- `--app-name` — substring of the app JVM display name (Gradle-ish descendant discovery)
- `--once` — exit after readiness succeeds (print window count) instead of holding the session

**Session lifetime (launch path):**

| Mode | Lifetime |
| --- | --- |
| Default (hold) | Prints pids, log paths, and window count; keeps the attach open until **Enter**, **EOF**, or interrupt; then detaches and kills the launched process tree. |
| `--once` | Same readiness attach, then exits immediately after the first window check (still tears down the process tree). |
| Ctrl-C / JVM exit | Shutdown hook tears down any published launch session. |

Launch is **self-contained**: it does not print a daemon session id for later `spectre click
$session` reuse from another shell. For multi-step scripting against a long-lived app, use
`ps` + `attach` instead of `launch`.

Stdout/stderr from the launched process are captured to files; their paths are printed on
success. Stage failures print a taxonomy error such as `[PROCESS_ALIVE]` with exit code and
stderr excerpt. See [Troubleshooting](troubleshooting.md#launch-and-attach-harness).

### Quick recipe (attach session)

For scripts and agents, add `--json` to a command that supports it. JSON output includes a
format version and stable field names; use the returned session ID and node keys rather than
parsing the human-readable output.

```shell
spectre ps --json
spectre attach <pid> --json
# … use the returned session ID …

spectre windows <session-id>
spectre tree <session-id> --json
spectre find <session-id> save-button --json
spectre find-text <session-id> "Save" --json
spectre wait-for-node <session-id> --tag save-button
spectre wait-for-visual-idle <session-id>
spectre click <session-id> <node-key>
spectre double-click <session-id> <node-key>
spectre long-click <session-id> <node-key> --hold-ms 500
spectre type <session-id> "A short note"
spectre press-key <session-id> 10          # KeyEvent.VK_ENTER
spectre scroll-wheel <session-id> <node-key> 3
spectre swipe <session-id> --from <from-key> --to <to-key>
spectre swipe <session-id> --start-x 100 --start-y 400 --end-x 100 --end-y 100

# Capture modes (see below) — do not conflate them
spectre screenshot <session-id> --fullscreen --output ./desktop.png
spectre capture <session-id> --json
spectre record start <session-id> --window 0 --output ./window.mp4
spectre record status <session-id>
spectre record stop <session-id>

spectre detach <session-id>
```

## Command reference

Every session command takes the session id as the first argument unless noted. Most support
`--json`. Prefer `spectre <command> --help` for the live option list.

### Discovery and lifecycle

| Command | Purpose | Important args / defaults |
| --- | --- | --- |
| `ps` | List attachable JVM processes | `--json` |
| `attach <pid>` | Attach daemon to target JVM; print session | `--json` → `id`, `pid` |
| `detach <session-id>` | End session; leftover capture report | `--json` includes `capturePaths`, `pruneCommand` |
| `launch -- <cmd…>` | Start process, readiness, attach, teardown | `-C`, `--app-name`, `--once` (see above) |
| `daemon status` | List live daemon sessions | `--json` |
| `daemon kill` | Stop daemon; discard all sessions | `--json` |
| `mcp` | Stdio MCP server over the shared daemon | No extra args; must not wrap stdout |

### Inspection and waits

| Command | Purpose | Important args / defaults |
| --- | --- | --- |
| `windows <session-id>` | Top-level windows and popup roots | Indices used by `--window` on capture/record |
| `tree <session-id>` | Current semantics nodes | Keys are ephemeral |
| `find <session-id> <test-tag>` | Exact Compose test-tag match | Single snapshot; does not wait |
| `find-text <session-id> <text>` | Find by visible/editable text | Exact match by default; `--substring` for contains |
| `wait-for-node <session-id>` | Poll until tag and/or text match | `--tag`, `--text` (at least one), `--timeout-ms` default **5000** |
| `wait-for-visual-idle <session-id>` | Wait until consecutive frames are stable | `--timeout-ms` default **5000** |
| `wait --reload-settled <session-id>` | Compose Hot Reload settle only | Requires `--reload-settled`; `--timeout-ms` default **60000**; fails closed when HR is not active |

Queries (`find`, `find-text`, `tree`) do a **single read** — they never retry. Use
`wait-for-node` when the UI is still settling.

### Input

Real OS input: can move focus, change app state, and interact with whatever is under the
pointer/keyboard. Prefer focusing the target app before keyboard ops when another process
owns the foreground.

| Command | Purpose | Important args / defaults |
| --- | --- | --- |
| `click <session-id> <node-key>` | Single click at node centre | Refresh keys after UI changes |
| `double-click <session-id> <node-key>` | Double-click | Same key rules as `click` |
| `long-click <session-id> <node-key>` | Long press | `--hold-ms` default **500** |
| `swipe <session-id>` | Drag node→node **or** screen coords | See [Swipe modes](#swipe-modes) |
| `scroll-wheel <session-id> <node-key> <wheel-clicks>` | Wheel at node centre | Positive = down; negative = up |
| `press-key <session-id> <key-code>` | Raw AWT key | `--modifiers` default **0**; see [Key codes](#press-key-awt-codes) |
| `type <session-id> <text>` | Type through OS input path | Target must accept keyboard focus |

#### Swipe modes

Exactly one mode per call — not both:

```shell
# Node centres (from / to keys from tree / find / wait-for-node)
spectre swipe <session-id> --from <from-node-key> --to <to-node-key> \
  --steps 12 --duration-ms 200

# Screen coordinates (AWT user-space pixels, same space as window bounds)
spectre swipe <session-id> \
  --start-x 100 --start-y 400 --end-x 100 --end-y 100 \
  --steps 12 --duration-ms 200
```

Defaults: `--steps 12`, `--duration-ms 200`. Mixing node keys with coordinates fails.

#### Press-key AWT codes

`press-key` takes a **`java.awt.event.KeyEvent` virtual-key integer** and an optional
**`java.awt.event.InputEvent` modifier mask** (not a key-code list of modifiers).

| Example | `key-code` | `--modifiers` |
| --- | --- | --- |
| Enter | `10` (`VK_ENTER`) | `0` |
| Tab | `9` (`VK_TAB`) | `0` |
| Escape | `27` (`VK_ESCAPE`) | `0` |
| Letter S | `83` (`VK_S`) | `0` |
| Ctrl+S | `83` | `128` (`CTRL_DOWN_MASK`) |
| Cmd+S (macOS) | `83` | `256` (`META_DOWN_MASK`) |
| Shift+Tab | `9` | `64` (`SHIFT_DOWN_MASK`) |

Other common masks: `ALT_DOWN_MASK` = `512`. Combine masks with bitwise OR when needed
(e.g. Ctrl+Shift). The library API documents the same values under
[Driving input](interactions.md#keyboard-typing-and-key-events). Keyboard delivery needs
OS focus on the target; attach clients that stay foreground may need the app focused first
(agent API: `focusWindow`; CLI has no separate focus command — activate the app window
manually or via a prior click).

### Capture and recording

Three different surfaces — pick intentionally:

| Surface | CLI | What you get | Targeting notes |
| --- | --- | --- | --- |
| **Fullscreen screenshot** | `screenshot … --fullscreen` | Single PNG of the **full virtual desktop** | **Only** screen-pixel mode on the attach/CLI path. Default / `--window` / `--surface` **fail closed** (occlusion/privacy risk). MCP returns inline PNG bytes, not a path. |
| **Atomic capture** | `capture` | Window PNG + full semantics tree on **daemon disk** (`capture.json` + `screenshot.png`) | `--window` default **0**. Summary only on stdout/MCP (paths + counts). See [Atomic capture](capture.md). |
| **Recording** | `record start` / `stop` / `status` | MP4 path on **daemon filesystem** | Default: tracked **window index 0**. `--fullscreen` = **primary display only** (not multi-monitor). Returns **paths**, never video bytes. |

```shell
# Full-desktop still (explicit opt-in)
spectre screenshot <session-id> --fullscreen --output ./desktop.png
# Without --output: temp PNG; path printed

# Atomic capture (window still + tree) — default root $TMPDIR/spectre/captures/
spectre capture <session-id> --window 0 --json
spectre capture <session-id> --out-dir ./my-captures --json

# Recording — window (default) vs primary display
spectre record start <session-id> --window 0 --output ./window.mp4
spectre record start <session-id> --fullscreen --output ./desktop.mp4
# Omit --output to allocate under the Spectre capture root (ledger + prune)
spectre record status <session-id> --json
spectre record stop <session-id>

spectre captures list [--all] [--json]
spectre captures prune --keep 20
spectre captures prune --session <session-id>
spectre captures prune --older-than 7d --include-out-dir --force
```

`captures list` shows size and live/closed status. `captures prune` supports `--keep N`,
`--older-than` (`30s` / `5m` / `24h` / `7d`), `--all`, `--session <id>`, `--force` (override
live-session guard), and `--include-out-dir` (client `--out-dir` roots are skipped unless
set). Prune refuses to run if the daemon cannot report live sessions.

Default-root captures are lazily capped (keep last 50 closed sessions' captures); client
`--out-dir` captures are never auto-deleted. Detach reports leftover captures and the exact
`captures prune --session` command. Summaries point agents at the **`spectre-capture`** skill
for `jq` recipes — see [Atomic capture](capture.md).

Recording needs the platform helper on the daemon host (`spectre-recording-macos` /
`spectre-recording-linux` / `spectre-recording-windows` as appropriate), plus OS permissions
(macOS Screen Recording TCC, Linux portal/X11 display, Windows helper runtimes). See
[Recording](recording.md) and [Recording limitations](../RECORDING-LIMITATIONS.md).

### macOS permissions

Local helpers only (no daemon). Safe on other OSes as “not applicable”.

| Command | Behaviour |
| --- | --- |
| `permissions check` | Non-interactive TCC preflight; exit **1** if Screen Recording not granted |
| `permissions request` | May open the system grant flow / helper guide — **run only with a human present** |

```shell
spectre permissions check --json
spectre permissions request
```

Capture and recording never open the grant UI implicitly; they preflight and fail closed.
See [Recording — macOS TCC](recording.md).

## MCP

Run `spectre mcp` when an MCP client should drive the UI. Tools share the **same per-user
daemon and session ids** as ordinary CLI commands.

```json
{
  "mcpServers": {
    "spectre": {
      "command": "/absolute/path/to/spectre",
      "args": ["mcp"]
    }
  }
}
```

`mcp` uses standard input and output for protocol messages. Do not wrap it in a command that
writes banners or logs to standard output.

When an agent also has Compose Hot Reload’s own MCP configured, follow the division of labor in
[Compose Hot Reload awareness](hot-reload.md): HR MCP for quick reload-native sanity checks;
Spectre for tree, input, capture, and evidence.

### Lifecycle

| Step | Tool / action |
| --- | --- |
| Discover | `list_processes` |
| Attach | `attach` → retain `sessionId` |
| Drive | `tree` / `find` / input tools / waits |
| Capture | `screenshot` (inline image), `capture` (paths on daemon FS), `record_*` (paths) |
| Release session | **`detach`** with `session_id` — ends that session and returns leftover capture cleanup summary (same honesty as CLI `spectre detach`). Unknown / already-detached / blank `session_id` **fail closed** (`isError`). Detach does **not** delete capture files; prune stays explicit. Use `spectre daemon kill` only when you intend to drop **every** session. |

Sessions created by MCP remain in the daemon until `detach`, daemon kill, or daemon
teardown. Long agent workflows should call **`detach`** when finished so session and native
resources do not leak. Successful detach leaves the **shared daemon running** so
`list_processes` and a new `attach` still work.

### Tool inventory

Inputs use snake_case JSON fields. Successful non-screenshot tools return **JSON text**
(serialized daemon response). Errors set MCP `isError` with a message.

| Tool | Required inputs | Optional inputs (defaults) | Success output shape |
| --- | --- | --- | --- |
| `list_processes` | — | — | `{ "processes": [ { pid, displayName, … } ] }` |
| `attach` | `pid` (integer) | — | `{ "sessionId", "targetPid" }` |
| `detach` | `session_id` (non-blank JSON string) | — | Daemon `Detached` JSON — see [Detach success body](#detach-success-body-mcp-vs-cli) below |
| `windows` | `session_id` | — | `{ "sessionId", "windows": […] }` |
| `tree` | `session_id` | — | `{ "sessionId", "nodes": […] }` |
| `find` | `session_id`, `test_tag` | — | nodes list (exact test tag) |
| `find_text` | `session_id`, `text` | `exact` (default **true**) | nodes list |
| `wait_for_node` | `session_id` | `tag`, `text`, `timeout_ms` (**5000**) | nodes when matched |
| `wait_for_visual_idle` | `session_id` | `timeout_ms` (**5000**) | `{ "sessionId" }` completed |
| `wait_for_reload_settled` | `session_id` | `timeout_ms` (**60000**) | completed; fails if HR inactive |
| `click` | `session_id`, `node_key` | — | completed |
| `double_click` | `session_id`, `node_key` | — | completed |
| `long_click` | `session_id`, `node_key` | `hold_for_ms` (**500**) | completed |
| `swipe` | `session_id` | node pair **or** coords; `steps` (**12**), `duration_ms` (**200**) | completed |
| `scroll_wheel` | `session_id`, `node_key`, `wheel_clicks` | — | completed; positive = down |
| `press_key` | `session_id`, `key_code` | `modifiers` (**0**) | completed; AWT codes as CLI |
| `type_text` | `session_id`, `text` | — | completed |
| `screenshot` | `session_id` | `window_index`, `surface_id`, `fullscreen` | **Inline PNG** `ImageContent` (not a path). Only `fullscreen=true` succeeds on attach; window/surface fail closed |
| `capture` | `session_id` | `window_index` (**0**), `out_dir`, `include_image` (**false**) | Summary JSON with **daemon-local paths** (`directory`, `captureJsonPath`, `screenshotPngPath`, counts…). Optional second content part: inline PNG when `include_image=true` |
| `record_start` | `session_id` | `output_path`, `window_index` (**0**), `fullscreen` (**false**) | `{ "sessionId", "outputPath" }` — path only |
| `record_stop` | `session_id` | — | final `outputPath` |
| `record_status` | `session_id` | — | `active`, optional `outputPath` / `captureDirectory` |

**Swipe (MCP):** pass either `from_node_key` + `to_node_key`, or all of
`start_x` / `start_y` / `end_x` / `end_y` — not both.

**Example — attach, find, detach:**

```json
// tools/call attach
{ "pid": 12345 }

// tools/call find
{ "session_id": "…", "test_tag": "save-button" }

// tools/call press_key  (Ctrl+S)
{ "session_id": "…", "key_code": 83, "modifiers": 128 }

// tools/call screenshot
{ "session_id": "…", "fullscreen": true }

// tools/call capture
{ "session_id": "…", "window_index": 0, "include_image": false }

// tools/call record_start
{ "session_id": "…", "fullscreen": true, "output_path": "/tmp/demo.mp4" }

// tools/call detach  (release session; does not delete files — run pruneCommand if needed)
{ "session_id": "…" }
```

### Detach success body (MCP vs CLI)

MCP `detach` success content is the daemon’s serialized **`DaemonResponse.Detached`** payload
(with `encodeDefaults`, plus the sealed-type discriminator field the codec emits). Field names
match the daemon protocol, not the CLI human line and not necessarily CLI `--json`:

| Field | MCP detach success | CLI `spectre detach --json` | Notes |
| --- | --- | --- | --- |
| Session id | `sessionId` | `id` | Same value; different key |
| Leftover count | `captureCount` | `captureCount` | Existing ledger dirs for that session only |
| Leftover bytes | `captureBytes` | `captureBytes` | Sum of ledger `sizeBytes` |
| Leftover paths | `capturePaths` | `capturePaths` | Absolute dirs still on disk |
| Prune command | `pruneCommand` (when leftovers) | `pruneCommand` | e.g. `spectre captures prune --session …` |
| Skill hint | `skillHint` (when leftovers) | `skillHint` | `spectre-capture` when leftovers exist |

Do **not** treat MCP text and CLI `--json` as byte-identical envelopes. CLI human output is a
prose line (`Detached <id>.` plus optional leftover lines), not this JSON.

**Honesty rules (shared with CLI detach):** when the session left capture artifacts on disk,
`captureCount` / `captureBytes` / `capturePaths` are non-zero and paths are real; `pruneCommand`
and `skillHint` are populated. Empty sessions report zeros and omit prune/skill. Detach never
auto-deletes files — run the reported prune command (or `spectre captures prune …`) explicitly.
Malformed `session_id` (missing, empty, whitespace-only, non-string) fails closed at the MCP tool
boundary without fabricating a summary.

Full tool row: [Tool inventory](#tool-inventory) above. Capture retention and prune flags:
[CLI capture](capture.md).

### Paths, filesystem, and capture modes (MCP)

- **Screenshot** returns image bytes over MCP. Nothing is written for the client to open by
  path unless the client saves the image itself.
- **`capture` and `record_*` write on the daemon host filesystem.** Paths in tool results are
  local to that machine (often `$TMPDIR/spectre/captures/…` when `out_dir` / `output_path` are
  omitted). They are **not** automatically transferred over MCP.
- **Shared-filesystem implication:** the MCP client process must be able to **read those paths**
  (same host, or a shared mount that both daemon and client see). Remote agents without a shared
  disk only get summaries / inline screenshots — use `include_image=true` on `capture` or
  `screenshot` with `fullscreen=true` when the client needs pixels in-band.
- **Recording** never streams video frames over MCP; only the final (or in-progress) path is
  reported. Ensure `spectre-recording-<os>` helpers and permissions exist on the **daemon** host.

Same capture-mode distinctions as the CLI table above apply to MCP tool names
(`screenshot` vs `capture` vs `record_start`).

## Requirements and limits

- `ps` and `attach` require **JDK 21+** in both the CLI and target JVM. The CLI needs a full
  JDK with `jdk.attach`; a JRE is not enough.
- **Target shape (two paths, one attach command):**
  1. **Preferred — preinstalled `spectre-core`** on the target (instrumented attach). Use this
     whenever you control the app build.
  2. **Experimental inject** — when core is absent but Compose/Skiko is present, the agent
     runtime can load a nested inject payload. Same `attach` surface; fine for inspect →
     detach, not for high-frequency CI attach loops. Details:
     [Agent attach — injection](agent.md#injection-without-preinstalled-core).
- Start the target JVM with **`-XX:+EnableDynamicAgentLoading`** to avoid JDK attach warnings
  and future incompatibility. The attaching process must be the **same OS user** as the target.
- The transport is local-only and trusts the operating-system user account. Use it only for
  trusted development and test environments.
- Input commands send real OS events. They can move focus and change application state.
- Node keys are short-lived. After an interaction changes the UI, run `tree` / `find` /
  `find-text` / `wait-for-node` again. On **reload-aware** sessions, keys are also invalidated
  after `wait --reload-settled` completes.
- `spectre wait --reload-settled` is meaningful only under Compose Hot Reload; otherwise it
  fails closed with `hotReloadUnavailable`. See [Compose Hot Reload awareness](hot-reload.md).
- Platform capture/recording needs helpers and permissions on the host that runs the daemon
  (see [Recording](recording.md)). macOS: `permissions check` / `request` for Screen Recording.

For the underlying attach model and its security boundary, see [Agent attach](agent.md).

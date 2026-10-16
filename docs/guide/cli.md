# CLI

## Inspecting and recovering the desktop input lease

!!! warning "Experimental control surface"
    Desktop input coordination and these control commands are experimental. The protocol, output,
    and recovery workflow may change in any release. See
    [Experimental desktop input coordination](input-coordination.md).

These commands inspect or change an existing coordinator. They do not start one:

```text
spectre input-lock status
spectre input-lock status --json
spectre input-lock revoke --lease <observed-id> --reason <text>
spectre input-lock revoke --lease <observed-id> --reason <text> --force
```

Revoke compares the supplied ID with the current lease. Copy the ID from `status`.
A stale ID cannot revoke a new holder. `--force` waits through a grace period and
reports audited `unsafeTakeover=true`. It does not prove an active native call has stopped.

The `spectre` command inspects and controls a running Compose Desktop application.
Use it to examine the semantics tree, debug interactions, or capture evidence without
writing a JUnit test. MCP clients can use the same operations.

The command starts a private local daemon when needed.
The daemon keeps sessions open across CLI calls and shares them with `spectre mcp`.

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

Run `spectre --help` after installation. Each command also supports `--help`.
Use the installed binary's help text for its exact arguments and options.

## What it can do

For a typical session:

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

Store the session ID. The CLI does not set a current session in the shell.

#### End-to-end: launch harness

`spectre launch` starts a command and checks process, JVM, agent, and window readiness.
It attaches Spectre and stops the launched process tree when the session ends.
Use `java -jar`, installDist, or a packaged app when possible.
Gradle `./gradlew :app:run` and `hotRun` emit warnings. They need `--app-name`
when the Gradle daemon starts the app JVM.

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
- `--once` — exit after readiness succeeds (print window count); the session does not remain open

**Session lifetime (launch path):**

| Mode | Lifetime |
| --- | --- |
| Default (hold) | Prints pids, log paths, and window count; keeps the attach open until **Enter**, **EOF**, or interrupt; then detaches and kills the launched process tree. |
| `--once` | Same readiness attach, then exits immediately after the first window check (still tears down the process tree). |
| Ctrl-C / JVM exit | Shutdown hook tears down any published launch session. |

Launch manages its own session. It does not return a daemon session ID for later commands
from another shell. Use `ps` and `attach` for a long-lived, multi-command session.

Stdout/stderr from the launched process are captured to files; their paths are printed on
success. Stage failures print a taxonomy error such as `[PROCESS_ALIVE]` with exit code and
stderr excerpt. See [Troubleshooting](troubleshooting.md#launch-and-attach-harness).

### Quick recipe (attach session)

Scripts and agents should request `--json` where available.
The response includes a format version and stable field names.
Use the response's session ID and node keys. Text output is intended for people.

```shell
spectre ps --json
spectre attach <pid> --json
# … use the returned session ID …

spectre windows <session-id>
spectre tree <session-id> --json
spectre find <session-id> save-button --json
spectre find-text <session-id> "Save" --json
spectre wait-for-node <session-id> --tag save-button
spectre wait-until-gone <session-id> --tag popup-body
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

Most session commands take the session ID first and support `--json`.
Run `spectre <command> --help` for the installed option list.

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
| `wait-until-gone <session-id>` | Poll until **nothing** matches tag and/or text | `--tag`, `--text` (at least one), `--timeout-ms` default **5000** |
| `wait-for-visual-idle <session-id>` | Wait until consecutive frames are stable | `--timeout-ms` default **5000** |
| `wait --reload-settled <session-id>` | Compose Hot Reload settle only | Requires `--reload-settled`; `--timeout-ms` default **60000**; fails closed when HR is not active |

`find`, `find-text`, and `tree` read the UI once.
Use `wait-for-node` when the expected node may appear later.

`wait-until-gone` is the absence counterpart: it returns once nothing matches, so use it after
dismissing a popup, menu, or dialog. It refreshes the tracked
windows before every poll, which is what lets it see a Compose popup that took its whole window
with it. On timeout it exits **5** and prints the selector, the timeout, and how many matching
nodes were still on screen:

```text
Spectre daemon error: Agent reported timeout for waitUntilGone: IllegalStateException: waitUntilGone timed out after 5000ms: 1 node(s) matching tag="popup.body" still present in tracked windows
```

### Input

Real OS input can change focus and application state. It reaches the current pointer
or keyboard target. Focus the target app before keyboard operations when another process
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

Each swipe call uses either node keys or screen coordinates:

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
**`java.awt.event.InputEvent` modifier mask**. Modifier key codes are invalid here.

| Example | `key-code` | `--modifiers` |
| --- | --- | --- |
| Enter | `10` (`VK_ENTER`) | `0` |
| Tab | `9` (`VK_TAB`) | `0` |
| Escape | `27` (`VK_ESCAPE`) | `0` |
| Letter S | `83` (`VK_S`) | `0` |
| Ctrl+S | `83` | `128` (`CTRL_DOWN_MASK`) |
| Cmd+S (macOS) | `83` | `256` (`META_DOWN_MASK`) |
| Shift+Tab | `9` | `64` (`SHIFT_DOWN_MASK`) |

Other common masks: `ALT_DOWN_MASK` = `512`. Combine masks with bitwise OR, such as for
Ctrl+Shift. [Driving input](interactions.md#keyboard-typing-and-key-events) lists the
same values for the library API. The target needs OS focus for keyboard input. An attach
client can keep focus, so the app may need focus first. The agent API provides
`focusWindow`. The CLI has no focus command; activate the window manually or click it.

### Capture and recording

The three capture commands return different outputs:

| Surface | CLI | Output | Targeting notes |
| --- | --- | --- | --- |
| **Fullscreen screenshot** | `screenshot … --fullscreen` | Single PNG of the **full virtual desktop** | Attach/CLI supports whole-desktop mode only. Default / `--window` / `--surface` **fail closed** (occlusion/privacy risk). MCP returns inline PNG bytes; no path is returned. |
| **Atomic capture** | `capture` | Window PNG + full semantics tree on **daemon disk** (`capture.json` + `screenshot.png`) | `--window` defaults to **0**. Stdout/MCP returns only a summary (paths + counts). See [Atomic capture](capture.md). |
| **Recording** | `record start` / `stop` / `status` | MP4 path on **daemon filesystem** | The default is tracked **window index 0**. `--fullscreen` captures the **primary display only**; multi-monitor recording is unsupported. Returns **paths**, never video bytes. |

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

All three normally write output in **screen pixels**. A 1600×1000dp window on a 2× display
produces a 3200×2000 PNG or MP4. `capture.json` reports PNG dimensions in
`window.imageWidth` and `imageHeight`. An oversized `--fullscreen` still can use logical
resolution to fit the attach frame budget. See [Atomic capture — Pixel scale](capture.md#pixel-scale).
The default budget is 64 MiB. Set `--max-frame-bytes` or `$SPECTRE_MAX_FRAME_BYTES` to
raise it; see [payload limits](agent.md#payload-limits-204).

`captures list` shows size and live/closed status. `captures prune` supports `--keep N`,
`--older-than` (`30s` / `5m` / `24h` / `7d`), `--all`, `--session <id>`, `--force` (override
live-session guard), and `--include-out-dir` (client `--out-dir` roots are skipped unless
set). Prune refuses to run if the daemon cannot report live sessions.

Spectre keeps at most the last 50 closed-session captures under the default root when
it applies the lazy cap. It never automatically deletes client `--out-dir` captures.
Detach reports leftover files and a `captures prune --session` command.
The [Atomic capture](capture.md) guide links to `spectre-capture` `jq` recipes.

Recording needs the platform helper on the daemon host (`spectre-recording-macos` /
`spectre-recording-linux` / `spectre-recording-windows` as appropriate), plus OS permissions
(macOS Screen Recording TCC, Linux portal/X11 display, Windows helper runtimes). See
[Recording](recording.md) and [Recording limitations](../RECORDING-LIMITATIONS.md).

### macOS permissions

These commands run locally without the daemon. On other operating systems, they report
“not applicable”.

| Command | Behaviour |
| --- | --- |
| `permissions check` | Non-interactive TCC preflight; exit **1** if Screen Recording not granted |
| `permissions request` | May open the system grant flow / helper guide — **run only with a human present** |

```shell
spectre permissions check --json
spectre permissions request
```

Capture and recording check permission and fail when it is missing.
They do not open the grant UI automatically.
See [Recording — macOS TCC](recording.md).

## MCP

Run `spectre mcp` to give an MCP client access to the UI.
Its tools use the **same per-user daemon and session IDs** as CLI commands.

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

If the agent also uses Compose Hot Reload's MCP, see
[Compose Hot Reload awareness](hot-reload.md). Use HR MCP to check reload state. Use Spectre
to inspect the tree, send input, and capture evidence.

### Lifecycle

| Step | Tool / action |
| --- | --- |
| Discover | `list_processes` |
| Attach | `attach` → retain `sessionId` |
| Drive | `tree` / `find` / input tools / waits |
| Capture | `screenshot` (inline image), `capture` (paths on daemon FS), `record_*` (paths) |
| Release session | `detach` with `session_id` ends that session and reports leftover captures, as CLI detach does. Unknown, detached or blank IDs return `isError`. Other sessions stay attached. Captures remain on disk until explicitly pruned. Use `spectre daemon kill` only to close all sessions. |

MCP sessions remain in the daemon until `detach`, daemon kill, or daemon teardown.
Closing the MCP frontend or its standard streams does not detach a session.
Call `detach` when finished to release session and native resources.
The shared daemon remains available for `list_processes` and new attaches.

**Preferred cleanup order (multi-step agents):**

1. **`record_stop`** if recording is active  
2. Finish or abandon long **`wait_for_*`** calls  
3. **`detach`** that `session_id`  

Concurrent tools on a session while `detach` runs **fail closed** (actionable errors; no hang).
Double-`detach` is the same fail-closed class as an unknown session.

### Tool inventory

Tool inputs use snake_case JSON fields. Successful tools other than screenshot return
serialised daemon JSON as text. Errors set MCP `isError` and include a message.

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
| `wait_until_gone` | `session_id` | `tag`, `text`, `timeout_ms` (**5000**) | `{ "sessionId" }` completed once nothing matches; `isError` on timeout, with the selector, timeout, and still-present count in the message |
| `wait_for_visual_idle` | `session_id` | `timeout_ms` (**5000**) | `{ "sessionId" }` completed |
| `wait_for_reload_settled` | `session_id` | `timeout_ms` (**60000**) | completed; fails if HR inactive |
| `click` | `session_id`, `node_key` | — | completed |
| `double_click` | `session_id`, `node_key` | — | completed |
| `long_click` | `session_id`, `node_key` | `hold_for_ms` (**500**) | completed |
| `swipe` | `session_id` | node pair **or** coords; `steps` (**12**), `duration_ms` (**200**) | completed |
| `scroll_wheel` | `session_id`, `node_key`, `wheel_clicks` | — | completed; positive = down |
| `press_key` | `session_id`, `key_code` | `modifiers` (**0**) | completed; AWT codes as CLI |
| `type_text` | `session_id`, `text` | — | completed |
| `screenshot` | `session_id` | `window_index`, `surface_id`, `fullscreen` | **Inline PNG** `ImageContent`; no path is returned. Only `fullscreen=true` succeeds on attach; window/surface fail closed |
| `capture` | `session_id` | `window_index` (**0**), `out_dir`, `include_image` (**false**) | Summary JSON with **daemon-local paths** (`directory`, `captureJsonPath`, `screenshotPngPath`, counts…). Optional second content part: inline PNG when `include_image=true` |
| `record_start` | `session_id` | `output_path`, `window_index` (**0**), `fullscreen` (**false**) | `{ "sessionId", "outputPath" }` — path only |
| `record_stop` | `session_id` | — | final `outputPath` |
| `record_status` | `session_id` | — | `active`, optional `outputPath` / `captureDirectory` |

**Swipe (MCP):** pass either `from_node_key` + `to_node_key`, or all of
`start_x` / `start_y` / `end_x` / `end_y`. Supplying both forms is invalid.

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

MCP `detach` success content is the daemon’s serialised **`DaemonResponse.Detached`** payload
(with `encodeDefaults`, plus the sealed-type discriminator field the codec emits). Field names
match the daemon protocol. The CLI human line and CLI `--json` can have different shapes:

| Field | MCP detach success | CLI `spectre detach --json` | Notes |
| --- | --- | --- | --- |
| Session id | `sessionId` | `id` | Same value; different key |
| Leftover count | `captureCount` | `captureCount` | Existing ledger dirs for that session only |
| Leftover bytes | `captureBytes` | `captureBytes` | Sum of ledger `sizeBytes` |
| Leftover paths | `capturePaths` | `capturePaths` | Absolute dirs still on disk |
| Prune command | `pruneCommand` (when leftovers) | `pruneCommand` | e.g. `spectre captures prune --session …` |
| Skill hint | `skillHint` (when leftovers) | `skillHint` | `spectre-capture` when leftovers exist |

Do **not** treat MCP text and CLI `--json` as byte-identical envelopes. CLI human output is a
prose line (`Detached <id>.` plus optional leftover lines). It does not use this JSON shape.

When the session left capture artefacts on disk,
`captureCount` / `captureBytes` / `capturePaths` are non-zero and paths are real; `pruneCommand`
and `skillHint` are populated. Empty sessions report zeros and omit prune/skill. Detach never
auto-deletes files. Run the reported prune command (or `spectre captures prune …`) explicitly.
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
  disk only get summaries / inline screenshots. Use `include_image=true` on `capture` or
  `screenshot` with `fullscreen=true` when the client needs pixels in-band.
- **Recording** never streams video frames over MCP; only the final (or in-progress) path is
  reported. Ensure `spectre-recording-<os>` helpers and permissions exist on the **daemon** host.

The CLI table's capture-mode distinctions also apply to MCP tool names
(`screenshot` vs `capture` vs `record_start`).

## Requirements and limits

- `ps` and `attach` require **JDK 21+** in both the CLI and target JVM. The CLI needs a full
  JDK with `jdk.attach`. A JRE lacks the required attach module.
- **Target shape (two paths, one attach command):**
  1. **Preferred — preinstalled `spectre-core`** on the target (instrumented attach). Use this
     for controlled app builds.
  2. **Experimental inject** — when core is absent and Compose/Skiko is present, the agent
     runtime can load a nested inject payload. The same `attach` command supports occasional
     inspect → detach sessions. High-frequency CI attach loops require preinstalled core. See
     [Agent attach — injection](agent.md#injection-without-preinstalled-core).
- Start the target JVM with **`-XX:+EnableDynamicAgentLoading`** to avoid JDK attach warnings
  and future incompatibility. The attaching process must be the **same OS user** as the target.
- The transport is local-only and trusts the operating-system user account. Use it only for
  trusted development and test environments.
- Input commands use the attached target's driver (synthetic AWT events by default;
  real OS events only if that target opted into `RobotDriver()`). They can still
  change application state.
- Node keys are short-lived. After an interaction changes the UI, run `tree` / `find` /
  `find-text` / `wait-for-node` again. On **reload-aware** sessions, keys are also invalidated
  after `wait --reload-settled` completes.
- `spectre wait --reload-settled` is meaningful only under Compose Hot Reload; otherwise it
  fails closed with `hotReloadUnavailable`. See [Compose Hot Reload awareness](hot-reload.md).
- Platform capture/recording needs helpers and permissions on the host that runs the daemon
  (see [Recording](recording.md)). macOS: `permissions check` / `request` for Screen Recording.

For the underlying attach model and its security boundary, see [Agent attach](agent.md).

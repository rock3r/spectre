# CLI

The `spectre` command is for inspecting and driving a running, Spectre-enabled Compose Desktop
application without first writing a JUnit test. It is useful for debugging a live UI, exploring
its semantics tree, capturing evidence while developing, and giving an MCP client access to the
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
authoritative reference for command arguments and options.

## What it can do

The CLI follows a simple loop:

1. Find a running JVM with `ps`.
2. Attach it and retain the returned session ID.
3. Inspect windows, the semantics tree, or nodes with a Compose test tag.
4. Use a current node key to click or type, then inspect again.
5. Capture a screenshot or recording when useful.

For scripts and agents, add `--json` to a command that supports it. JSON output includes a
format version and stable field names; use the returned session ID and node keys rather than
parsing the human-readable output.

```shell
# Find a target JVM, then attach it.
spectre ps --json
spectre attach <pid> --json

# Use the returned session ID to inspect and drive the UI.
spectre windows <session-id>
spectre tree <session-id> --json
spectre find <session-id> save-button --json
spectre click <session-id> <node-key>
spectre type <session-id> "A short note"

# Keep visual evidence (default: tracked window index 0, not the whole desktop).
spectre screenshot <session-id> --output ./after-save.png
# Target a specific window from `spectre windows --json`, or opt into full desktop.
spectre screenshot <session-id> --window 0 --output ./window.png
spectre screenshot <session-id> --surface window:0 --output ./surface.png
spectre screenshot <session-id> --fullscreen --output ./desktop.png
# Atomic capture: window PNG + full semantics tree on disk (summary only on stdout).
spectre capture <session-id> --json
# List leftover capture dirs (sizes + live/closed status).
spectre captures list
# Prune closed-session captures on the default root (never touches live sessions without --force).
spectre captures prune --keep 20
# When the target runs under Compose Hot Reload: wait for a full settle, then re-tree.
spectre wait --reload-settled <session-id>
spectre tree <session-id> --json
spectre record start <session-id> --output ./interaction.mp4
# Window is default (index 0); --fullscreen is opt-in full primary display.
spectre record start <session-id> --window 0 --output ./window.mp4
spectre record start <session-id> --fullscreen --output ./desktop.mp4
spectre record stop <session-id>
```

`tree` lists the current semantics nodes. `find` performs an exact match on a Compose test tag.
`windows` includes top-level windows and popup roots. `screenshot` captures a tracked window of the
attached session (default index `0`) and writes a PNG to `--output`, or creates a temporary PNG and
prints its path. Pass `--window <index>` or `--surface <surfaceId>` (as returned by
`spectre windows --json`) to target a specific surface. Full virtual-desktop capture is opt-in only
via `--fullscreen`; if window capture cannot be performed, the command fails rather than silently
writing a full-screen image. `capture` takes a window screenshot and the semantics
tree under the same tick, writes `capture.json` + `screenshot.png` under a sequenced capture
directory (`NNNN-<timestamp>/` under `$TMPDIR/spectre/captures` by default, mode `0700`, or under
`--out-dir`), appends a crash-proof ledger entry, and returns only a decision-grade summary
(paths, node counts, image size). Default-root captures are lazily capped (keep last 50 closed
sessions' captures); client `--out-dir` captures are never auto-deleted. Summaries and detach
reports point agents at the **`spectre-capture`** skill for `jq` recipes — see
[Atomic capture](capture.md). `record start` records a tracked window by default (index `0`;
`--window` / `--window-index`). Full-display recording is opt-in via `--fullscreen` (primary
display only; multi-monitor desktops are rejected until multi-display capture is supported).

`spectre captures list [--all] [--json]` lists ledger-backed capture directories with size and
live/closed status. `spectre captures prune` supports `--keep N`, `--older-than 7d`, `--all`,
`--session <id>`, and `--force` (override the live-session guard). Out-dir captures are skipped
unless you pass `--include-out-dir`.

Use `spectre detach <session-id>` when the session is no longer useful; the detach report lists
that session's leftover captures and the exact `captures prune --session` command. `spectre daemon
status` lists the daemon's sessions, and `spectre daemon kill` stops the daemon and discards all of
them.

## MCP

Run `spectre mcp` when an MCP client should drive the UI. It exposes process discovery, attach,
window/tree lookup, test-tag lookup, click, text input, screenshots, recording, atomic capture,
and optional Compose Hot Reload settle (`wait_for_reload_settled`) as tools. Screenshots are
returned to MCP clients as inline PNG images.

When an agent also has Compose Hot Reload’s own MCP configured, follow the division of labor in
[Compose Hot Reload awareness](hot-reload.md): HR MCP for quick reload-native sanity checks;
Spectre for tree, input, capture, and evidence.

For example, configure a client with the absolute executable path:

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

## Requirements and limits

- `ps` and `attach` require JDK 21 or newer in both the CLI and target JVM. The CLI needs a full
  JDK with `jdk.attach`; a JRE is not enough.
- The target app must already use Spectre `core`, and it must run as the same OS user as the CLI.
  Start the target JVM with `-XX:+EnableDynamicAgentLoading` to avoid JDK attach warnings and
  future incompatibility.
- The transport is local-only and trusts the operating-system user account. Use it only for
  trusted development and test environments.
- `click` and `type` send real operating-system input to the target UI. They can move focus and
  change application state.
- Node keys are short-lived. After an interaction changes the UI, run `tree` or `find` again
  before using another node key. On **reload-aware** sessions (Compose Hot Reload detected at
  attach), keys are also invalidated after `wait --reload-settled` completes — always re-query.
- `spectre wait --reload-settled <session-id>` is meaningful only when the attached process is
  running under Compose Hot Reload; otherwise it fails closed with `hotReloadUnavailable`. See
  [Compose Hot Reload awareness](hot-reload.md).
- The CLI attaches only to JVMs. It does not make a non-Compose or non-Spectre application
  inspectable.

For the underlying attach model and its security boundary, see [Agent attach](agent.md).

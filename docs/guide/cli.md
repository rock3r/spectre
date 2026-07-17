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

# Keep visual evidence.
spectre screenshot <session-id> --output ./after-save.png
spectre record start <session-id> --output ./interaction.mp4
spectre record stop <session-id>
```

`tree` lists the current semantics nodes. `find` performs an exact match on a Compose test tag.
`windows` includes top-level windows and popup roots. `screenshot` writes a PNG to `--output`, or
creates a temporary PNG and prints its path. `record start` behaves similarly for an MP4.

Use `spectre detach <session-id>` when the session is no longer useful. `spectre daemon status`
lists the daemon's sessions, and `spectre daemon kill` stops the daemon and discards all of them.

## MCP

Run `spectre mcp` when an MCP client should drive the UI. It exposes process discovery, attach,
window/tree lookup, test-tag lookup, click, text input, screenshots, and recording as tools.
Screenshots are returned to MCP clients as inline PNG images.

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

- `ps` and `attach` require a full JDK 21 or newer with `jdk.attach`; a JRE is not enough.
- The target app must already use Spectre `core`, and it must run as the same OS user as the CLI.
  Start the target JVM with `-XX:+EnableDynamicAgentLoading` to avoid JDK attach warnings and
  future incompatibility.
- The transport is local-only and trusts the operating-system user account. Use it only for
  trusted development and test environments.
- `click` and `type` send real operating-system input to the target UI. They can move focus and
  change application state.
- Node keys are short-lived. After an interaction changes the UI, run `tree` or `find` again
  before using another node key.
- The CLI attaches only to JVMs. It does not make a non-Compose or non-Spectre application
  inspectable.

For the underlying attach model and its security boundary, see [Agent attach](agent.md).

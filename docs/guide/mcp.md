# MCP access

Run `spectre mcp` to expose a running Compose Desktop application to an MCP client. The
server uses standard input and output exclusively for MCP protocol frames; do not wrap it
with a command that writes banners or logs to standard output.

The MCP server and the `spectre` CLI share the same per-user session daemon. Starting either
one starts the daemon when needed, and sessions attached through one are available to the
other. The daemon is stopped with `spectre daemon kill`.

## Claude Code recipe

Install the Spectre CLI distribution, then add this project-local MCP configuration:

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

Restart Claude Code after saving the configuration. Start your Compose Desktop application
with the Spectre agent enabled, then ask Claude to list Spectre processes and attach to the
target PID.

The server provides `list_processes`, `attach`, `windows`, `tree`, `find`, `click`,
`type_text`, `screenshot`, `record_start`, and `record_stop`. `screenshot` returns PNG bytes
as inline image content, so Claude can inspect the captured UI directly. Node keys returned by
`tree` and `find` are valid only until the UI changes; refresh the tree before reusing a key.

Use `record_start` with an output path and `record_stop` to save a recording. MCP errors retain
the daemon's message, including missing sessions and unavailable operations.

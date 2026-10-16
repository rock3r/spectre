# Compose Hot Reload awareness

Spectre can observe [Compose Hot Reload](https://github.com/JetBrains/compose-hot-reload)
when the target application runs under Hot Reload. The CLI, daemon, and MCP tools also work
with ordinary JVM targets. Reload waits report an error when Hot Reload is unavailable.

Only the interactive `spectre` CLI, daemon, and MCP tools observe reloads.
The `:testing` and JUnit APIs do not include reload waits.
Write repeatable tests that pass without Hot Reload.

## Tool responsibilities

When both MCP servers are available, use each for its supported operations:

> Use Compose Hot Reload MCP to check recomposition during a live Hot Reload session.
> Use Spectre for other inspection and automation tasks.

| Goal | Prefer |
|---|---|
| Quick “did this recompose?” / HR-native reload plumbing while editing | Compose Hot Reload MCP |
| Semantics tree, node keys, real input, screenshots, recording, capture, attach to any Spectre-enabled JVM | Spectre |
| Wait until a reload has fully settled, then re-inspect with Spectre | Spectre `wait --reload-settled` / `wait_for_reload_settled` |
| Repeatable automated tests | Spectre **without** HR (`:testing` / JUnit) |

The `spectre-capture` skill includes the same tool guidance beside its capture steps.

## When reload awareness activates

During `spectre attach` or MCP attach, the daemon checks the target JVM arguments for a Hot Reload
orchestration endpoint. Tests and advanced callers can also provide an explicit pid-file path:

1. System property `compose.reload.orchestration.port` on the process command line
2. System property `compose.reload.pidFile` pointing at HR’s pid file, which must contain
   `orchestration.port` (and, when present, a matching `pid`)

The daemon reads only the path named by the property or an explicit override.
It does not search default locations for a pid file.

When it finds a port, the daemon connects a **Tooling** role client through the public
`hot-reload-orchestration` API. The attached session can then observe reloads:

- `spectre wait --reload-settled <session>` / MCP `wait_for_reload_settled` wait for a full settle
- Node keys returned by `tree` / `find` (and wait-for-node) are **generation-stamped** and
  invalidated after a successful reload settles

Attach succeeds when the daemon finds no Hot Reload endpoint.
Other Spectre commands still work. Reload wait returns `hotReloadUnavailable` immediately.

## Version range

The CLI uses and tests Compose Hot Reload **1.2.0**.
Support begins at **1.2.0-alpha+211**, which provides the required Tooling client and settle messages.
Older builds may omit those messages, so Spectre reports a failure.

Use a supported Hot Reload plugin and runtime version in the target application.

## `waitForReloadSettled`

Spectre observes the Hot Reload settle sequence:

`ReloadClassesRequest` → matching `ReloadClassesResult` → matching `UIRendered` → `Ping` / `Ack`
drain.

### CLI

The wait must observe `ReloadClassesRequest` and the later settle events.
Start it **before** triggering a reload:

```shell
spectre attach <pid> --json
# Terminal A — arm the wait first (blocks until settle or timeout):
spectre wait --reload-settled <session-id>
# optional: --timeout-ms 60000 --json
# Terminal B / IDE — then trigger the hot reload (save, apply changes, etc.)
```

On success, the command reports completion in text or JSON with the session ID.
If the reload finishes before the wait subscribes, the command waits for the next reload
or reaches its timeout.

### MCP

Tool name: **`wait_for_reload_settled`**

| Argument | Required | Default |
|---|---|---|
| `session_id` | yes | — |
| `timeout_ms` | no | `60000` |

### Error categories

| Category | Meaning |
|---|---|
| `hotReloadUnavailable` | Session is **not** reload-aware: attach found no HR orchestration port/pid-file properties, so no Hot Reload client was created |
| `reloadFailed` | HR reported an unsuccessful class reload (`ReloadClassesResult` with failure) |
| `timeout` | Settle chain did not complete within `--timeout-ms` / `timeout_ms`. Also returned when the session is reload-aware (properties present) and the Tooling client never connects or the settle events never arrive, including stale/unreachable advertised ports |
| `cancelled` | Session closed or the orchestration connection dropped while waiting |

## Node keys after reload

On reload-aware sessions, node keys from **`tree` / `find`** (and wait-for-node) are **stamped**
with a generation prefix (`g{n}:…`). After a successful reload settle:

1. Spectre advances the generation and clears the issued-key allowlist.
2. Pre-reload keys fail closed as `nodeNotFound` (including guessed stamps for the new generation
   until a fresh tree is issued).
3. Call `tree` or `find` again, then use the new stamped keys for input.

**Atomic capture** writes a window PNG and full semantics tree.
The node keys in `capture.json` are raw tree keys. They lack the generation stamp needed
for clicks on reload-aware sessions. After a reload, call `tree` or `find` for click keys.
Use capture files to inspect evidence with `jq`.

Sessions without reload awareness use unstamped keys. UI changes can still invalidate them.

If a reload overlaps a tree query, the daemon may return `reloadRace` because it cannot
assign a consistent generation. Retry `tree` or `find`.

## Layering rules (non-negotiable)

- **HR is an optional capability.** Semantics reading, attach, input, capture, and
  recording work without it.
- **HR is dev-loop only.** Debug runs, no production guarantee, no CI repeatability contract.
- **Banned from `:testing`.** There is no `waitForReloadSettled` on `ComposeAutomator` / JUnit
  extensions. Use `waitForNode` / `waitForIdle` / `waitForVisualIdle` in tests.
- **Spectre never redefines classes.** Coexistence with HR’s agent is intentional: Spectre does
  not trip HR’s external-reload trackers.

## Launching under Hot Reload

For ordinary automation, launch `installDist`, a packaged app, or plain `java -jar`.
[`spectre launch`](cli.md#launch-and-attach) supports Gradle `run` and `hotRun` with warnings.
Use `--app-name` when a Gradle daemon starts the app JVM.

For the edit–reload–inspect loop:

1. Start the app under Compose Hot Reload (IDE run configuration or Gradle `hotRun`).
2. `spectre attach <pid>` (daemon session; HR wait runs on the CLI/daemon path.
   The in-process launch automator has no HR wait).
3. Arm `spectre wait --reload-settled <session>` **before** each reload, then re-query the tree.

## Manual dual-MCP recipe

Use this when validating an agent that has **both** HR MCP and Spectre configured:

1. Start the Compose app under Hot Reload (IDE run configuration or `hotRun`).
2. Confirm Spectre can see it: `spectre ps --json` → `spectre attach <pid> --json`.
3. For a **quick HR-native sanity check** (reload plumbing only), use the HR MCP tools.
4. For **tree / click / screenshot / capture-as-evidence**, use Spectre only — do not alternate
   randomly.
5. Before an intentional code reload: start `spectre wait --reload-settled <session>` (blocking
   call), then trigger the reload; when wait returns, run `spectre tree` / `spectre find` and
   act on **new** stamped keys. Use `capture` for screenshots and `jq` inspection if useful.
   Resolve post-reload click keys with `tree` or `find`.
6. Detach when finished; prune captures after atomic capture.

## Related

- [CLI](cli.md) — `wait --reload-settled`, attach, tree, capture
- [Agent attach](agent.md) — attach model and MCP tool list
- [Synchronization](synchronization.md) — in-process waits (no HR wait on `:testing`)
- [Atomic capture](capture.md) and skill **`spectre-capture`**
- [Troubleshooting](troubleshooting.md#compose-hot-reload)

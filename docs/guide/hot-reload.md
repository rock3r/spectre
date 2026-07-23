# Compose Hot Reload awareness

Spectre can build on [Compose Hot Reload](https://github.com/JetBrains/compose-hot-reload)
**when the target app is already running under HR**. It never assumes HR is present: the same
CLI, daemon, and MCP surfaces work on ordinary JVM targets, and HR-only waits fail closed with a
clear error.

Reload awareness lives in the **interactive** tier only (`spectre` CLI / daemon / MCP). It is
**not** part of the `:testing` / JUnit surface. A test that needs hot reload to pass is not a
reliable test.

## Division of labor

If you configure both Compose Hot Reload’s MCP server and Spectre’s MCP (or CLI) in the same
agent session, use this rule — do not flip a coin per call:

> **If you have HR available and want quick sanity checks while iterating on a live app, use the
> HR MCP; in any other case, Spectre is the right choice.**

| Goal | Prefer |
|---|---|
| Quick “did this recompose?” / HR-native reload plumbing while editing | Compose Hot Reload MCP |
| Semantics tree, node keys, real input, screenshots, recording, capture, attach to any Spectre-enabled JVM | Spectre |
| Wait until a reload has fully settled, then re-inspect with Spectre | Spectre `wait --reload-settled` / `wait_for_reload_settled` |
| Repeatable automated tests | Spectre **without** HR (`:testing` / JUnit) |

The same rule is repeated in the shipped **`spectre-capture`** agent skill so dual-MCP agents see
it next to capture workflows.

## When reload awareness activates

On `spectre attach` (and the MCP `attach` tool), the daemon tries to discover an HR orchestration
endpoint for that process from the target’s JVM arguments (and an optional explicit pid-file path
used by tests / advanced callers):

1. System property `compose.reload.orchestration.port` on the process command line
2. System property `compose.reload.pidFile` pointing at HR’s pid file, which must contain
   `orchestration.port` (and, when present, a matching `pid`)

There is **no** scan of a default filesystem location for a pid file — only the property-backed
path (or an explicit override) is read.

If a port is found, the daemon opens a **Tooling**-role orchestration client (same public
`hot-reload-orchestration` surface HR’s own tooling uses). That session is **reload-aware**:

- `spectre wait --reload-settled <session>` / MCP `wait_for_reload_settled` wait for a full settle
- Node keys returned by `tree` / `find` (and wait-for-node) are **generation-stamped** and
  invalidated after a successful reload settles

If no HR orchestration is found, attach still succeeds. The session is ordinary Spectre: all
non-HR commands work; reload wait fails immediately with category `hotReloadUnavailable`.

## Version range

Spectre pins and tests against Compose Hot Reload **1.2.0** on the CLI classpath. The
supported line starts at **1.2.0-alpha+211** (the findings baseline for the Tooling client and
settle chain). Older HR builds may omit newer orchestration messages; Spectre fails closed rather
than guessing.

Keep the app’s HR plugin/runtime on a version in that range when you want reload awareness.

## `waitForReloadSettled`

Spectre mirrors HR’s own settle chain (not a simple “reload state” poll):

`ReloadClassesRequest` → matching `ReloadClassesResult` → matching `UIRendered` → `Ping` / `Ack`
drain.

### CLI

The wait only observes reloads that start **while it is running** (it must see
`ReloadClassesRequest` through the settle chain). Start the wait **before** you save/reload,
not after:

```shell
spectre attach <pid> --json
# Terminal A — arm the wait first (blocks until settle or timeout):
spectre wait --reload-settled <session-id>
# optional: --timeout-ms 60000 --json
# Terminal B / IDE — then trigger the hot reload (save, apply changes, etc.)
```

On success the wait prints that the reload settled (or JSON completion with the session id).
If you reload first and only then call `wait`, a fast settle can finish before the waiter
subscribes and the command will sit until the **next** reload or time out.

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
| `timeout` | Settle chain did not complete within `--timeout-ms` / `timeout_ms`. Also returned when the session is reload-aware (properties present) but the Tooling client never connects or the settle events never arrive — including stale/unreachable advertised ports |
| `cancelled` | Session closed or the orchestration connection dropped while waiting |

## Node keys after reload

On reload-aware sessions, node keys from **`tree` / `find`** (and wait-for-node) are **stamped**
with a generation prefix (`g{n}:…`). After a successful reload settle:

1. Spectre advances the generation and clears the issued-key allowlist.
2. Pre-reload keys fail closed as `nodeNotFound` (including guessed stamps for the new generation
   until a fresh tree is issued).
3. Call `tree` or `find` again, then use the new stamped keys for input.

**Atomic capture** still writes the window PNG and a full semantics tree for inspection, but
`capture.json` node keys are the raw tree keys — they are **not** generation-stamped for click
dispatch on reload-aware sessions. After a reload settle, resolve keys with `tree` / `find`
(or MCP equivalents) before clicking; use capture for evidence and `jq` recipes, not as the
source of post-reload click keys.

On non-reload-aware sessions, keys stay unstamped and behave as before: still short-lived across
UI changes, but not generation-gated by HR.

If a tree query races a settle so hard that Spectre cannot stamp a consistent generation, the
daemon fails closed with category `reloadRace` — re-run `tree` / `find` rather than treating an
empty result as “no nodes”.

## Layering rules (non-negotiable)

- **HR is a capability, not a dependency.** Semantics reading, attach, input, capture, and
  recording work without it.
- **HR is dev-loop only.** Debug runs, no production guarantee, no CI repeatability contract.
- **Banned from `:testing`.** There is no `waitForReloadSettled` on `ComposeAutomator` / JUnit
  extensions. Use `waitForNode` / `waitForIdle` / `waitForVisualIdle` in tests.
- **Spectre never redefines classes.** Coexistence with HR’s agent is intentional: Spectre does
  not trip HR’s external-reload trackers.

## Launching under Hot Reload

Prefer a **prod-like** launch for everyday automation (`installDist`, packaged app, plain
`java -jar`) when you do not need HR — including [`spectre launch`](cli.md#launch-and-attach).
Gradle `run` / `hotRun` work via launch with warnings and `--app-name` when the app JVM is
daemon-spawned.

For the edit–reload–inspect loop:

1. Start the app under Compose Hot Reload (IDE run configuration or Gradle `hotRun`).
2. `spectre attach <pid>` (daemon session — HR wait is on the CLI/daemon path, not the in-process
   launch automator).
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
   act on **new** stamped keys. Use `capture` for screenshots and `jq` inspection if useful —
   not for post-reload click keys.
6. Detach when finished; prune captures if you used atomic capture.

## Related

- [CLI](cli.md) — `wait --reload-settled`, attach, tree, capture
- [Agent attach](agent.md) — attach model and MCP tool list
- [Synchronization](synchronization.md) — in-process waits (no HR wait on `:testing`)
- [Atomic capture](capture.md) and skill **`spectre-capture`**
- [Troubleshooting](troubleshooting.md#compose-hot-reload)

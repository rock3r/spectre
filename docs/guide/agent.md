# Agent attach (experimental)

Spectre's `:agent` module lets you attach to a **running**, Spectre-instrumented JVM and
drive its UI from a separate process — no need to mount routes at startup, no HTTP, no
network listener.

This is the right transport when:

- Your test JVM and the UI JVM are different processes by design, but you don't want to
  modify the UI app's startup wiring.
- You want to inspect a long-running Spectre-aware app interactively through the
  `spectre` CLI or an MCP client.
- You're driving an IntelliJ-hosted Compose surface from a sister process. *Note: see the
  current limitations below — IntelliJ support is gated until further validation.*

For comparison with the other transports, see [Cross-JVM access](cross-jvm.md) (HTTP) and
[IntelliJ-hosted Compose](intellij.md) (in-process via `intellij-ide-starter`). Which
operations are **Supported** vs **Unsupported by design** vs **Not yet CI-executed** is
tracked in the [capability matrix](capability-matrix.md) — every Supported cell must have
executable CI evidence.

!!! warning "Experimental API"

    Everything under `dev.sebastiano.spectre.agent.*` is annotated
    `@ExperimentalSpectreAgentApi` and requires explicit opt-in. The API may change in any
    release until the UX stabilizes. See [Stability policy](../STABILITY.md).

!!! warning "Trust boundary"

    The agent transport is **local only** and intended for **trusted dev/test
    environments**. Trust model:

    - Communication is over a **Unix Domain Socket** under a short private directory (`/tmp/` on
      Linux/macOS, `%TEMP%` on Windows). Filesystem permissions are the only access control —
      directory mode 0700 / socket mode 0600 on POSIX, or an owner-only ACL on Windows/NTFS.
    - The attaching JVM must run as the same OS user as the target JVM.
    - There is **no authentication** and **no encryption** on the wire.
    - The published `spectre-agent` API jar is for the attaching JVM. The
      `spectre-agent-runtime` jar gets loaded into the target JVM. See
      [Artifact roles](#artifact-roles) below.

    See [Security notes](../SECURITY.md) for the full risk register.

## Requirements

- **JDK 21+** on both the attaching and target JVMs.
- The attaching JVM must be a **JDK** (not a JRE) with the `jdk.attach` module on the
  module graph.
- The target JVM must include **Spectre `:core`** on its classpath. The agent does not
  inject Spectre into the target; it reflectively bootstraps off the `:core` that's
  already loaded. The agent JAR itself is supplied by the attaching JVM at attach time.
- **Linux, macOS, and Windows.** The transport uses native Unix Domain Sockets (`AF_UNIX`) on all
  three — no named pipes, no extra dependencies. Windows requires **Windows 10 version 1803 /
  Windows Server 2019 or newer**, when native `AF_UNIX` landed; older Windows fails the attach
  preflight with a clear message.
- The target JVM should be started with **`-XX:+EnableDynamicAgentLoading`**. Without it,
  attach prints a stderr warning per
  [JEP 451](https://openjdk.org/jeps/451) and a future JDK will reject the attach
  entirely.

## Artifact roles

Agent attach involves two JVMs:

- **Target JVM** — the Compose app you want to inspect or drive.
- **Attacher JVM** — the test, inspector, or tool process that calls `AgentAttach.attach(pid)`.

The target JVM must already have Spectre `:core` on its classpath. The agent runtime does
not inject `:core`; it reflectively locates `ComposeAutomator` in the target's classloader
after it has been loaded into that JVM.

```kotlin
// build.gradle.kts of the target application
dependencies {
    implementation("dev.sebastiano.spectre:spectre-core:<version>")
    // No `spectre-agent` or `spectre-agent-runtime` dependency is needed in the target.
    // The attacher supplies the runtime jar to the JDK Attach API.
}
```

The attacher JVM usually needs two artifacts:

- `spectre-agent` — the normal API jar that your test/inspector code compiles against.
- `spectre-agent-runtime` — the loadable Java-agent runtime jar that gets passed to
  `VirtualMachine.loadAgent(...)`.

The easiest Gradle shape is a normal implementation dependency plus a runtime-only dependency on
the loadable runtime artifact:

```kotlin
dependencies {
    implementation("dev.sebastiano.spectre:spectre-agent:<version>")
    runtimeOnly("dev.sebastiano.spectre:spectre-agent-runtime:<version>")
}
```

`AgentAttach` resolves the loadable runtime jar in this order:

1. `AttachOptions.agentJarPath`
2. `-Ddev.sebastiano.spectre.agent.runtimeJar=<path>`
3. Classpath auto-discovery of a physical `spectre-agent-runtime-<version>.jar`
4. The in-repo fallback at `<cwd>/agent-runtime/build/libs/agent-runtime-*.jar`

In normal Gradle usage, `runtimeOnly(...)` makes Gradle launch the attacher with the runtime jar
listed in `java.class.path`; Spectre scans that classpath, takes the physical jar path, and passes
that path to `VirtualMachine.loadAgent(...)`. The attacher does not call classes from the runtime
jar directly, and the target still does not need `spectre-agent-runtime` declared as a dependency.

## How attach works

`AgentAttach.attach(pid)` performs this sequence:

1. Resolve the loadable `spectre-agent-runtime-<version>.jar`.
2. Create a fresh Unix Domain Socket path such as `/tmp/sp-a-<pid>-<8char-uuid>/agent.sock`.
3. Run attach preflights, including the same-OS-user check.
4. Call `VirtualMachine.attach(pid).loadAgent(runtimeJarPath, udsPath)`.
5. The target JVM loads the runtime jar and invokes `SpectreAgent.agentmain(...)`.
6. Inside the target JVM, `SpectreAgent` finds `ComposeAutomator` from the target's existing
   `:core` dependency, creates an in-process automator, and starts an IPC server on the UDS path.
7. The attacher connects an `IpcClient` to that socket and returns `AttachedAutomator`.

After that, calls such as `windows()`, `findByTestTag(...)`, `click(...)`, and `screenshot()` are
small CBOR requests over the socket. They execute inside the target JVM against the in-process
automator, then return DTOs or bytes to the attacher.

## Custom runtime jar path

Classpath auto-discovery is the default for normal Gradle runs, but `AttachOptions.agentJarPath`
and `-Ddev.sebastiano.spectre.agent.runtimeJar=<path>` are explicit overrides and win before the
classpath scan. Use them for custom launchers, shaded tools, module-path launches, and ad-hoc
scripts that hide the physical runtime jar from `java.class.path`.

```kotlin
import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachOptions
import java.nio.file.Path

AgentAttach.attach(
    pid = targetPid,
    options =
        AttachOptions(
            agentJarPath = Path.of("/abs/path/to/spectre-agent-runtime-<version>.jar"),
        ),
)
```

Equivalent: set `-Ddev.sebastiano.spectre.agent.runtimeJar=<path>` on the attacher's JVM.

When working inside the Spectre repo, `AgentAttach` also falls back to
`<cwd>/agent-runtime/build/libs/agent-runtime-*.jar` so local manual recipes keep working after
`./gradlew :agent-runtime:jar`.

Consumers that cannot use the published Maven coordinate still have two supported paths:

1. **As a project dependency** (you're inside the Spectre repo or a Gradle composite
   build that includes it):

    ```kotlin
    // build.gradle.kts of the test/attacher module
    dependencies {
        implementation(projects.agent)
        runtimeOnly(projects.agentRuntime)
    }
    ```

2. **As an explicit path** via `AttachOptions.agentJarPath` or
   `dev.sebastiano.spectre.agent.runtimeJar`, as shown above.

Start the target with the dynamic-agent flag (suppresses the JEP 451 stderr warning):

```bash
java -XX:+EnableDynamicAgentLoading -jar my-spectre-app.jar
```

## Attaching

In the attaching JVM (typically a test process), opt in to the experimental API and use
`AgentAttach.attach`:

```kotlin
@file:OptIn(ExperimentalSpectreAgentApi::class)

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreProcesses

// Find the target by name.
val target = SpectreProcesses.findByName("MyApp").single()

AgentAttach.attach(target.pid).use { automator ->
    val windows = automator.windows()
    val submitNodes = automator.findByTestTag("Submit")
    if (submitNodes.isNotEmpty()) {
        automator.click(submitNodes.first().key)
    }
    val pngBytes = automator.screenshot()
} // detach + cleanup on close()
```

`AttachedAutomator` is `AutoCloseable`. Closing it sends an `AgentRequest.Detach` over the
wire; the agent stops accepting new requests, releases its `ComposeAutomator`, unlinks
the UDS path, and removes its shutdown hook. A target-side shutdown hook covers crash
cleanup.

### `AttachOptions`

```kotlin
AttachOptions(
    agentJarPath = null,        // null = auto-locate (see "Artifact roles" above)
    udsPath = null,             // null = <tmp>/sp-a-<pid>-<8char-uuid>/agent.sock (/tmp on POSIX, %TEMP% on Windows)
    attachTimeoutMs = 5_000,    // how long to wait for the agent's IPC server to come up
)
```

If you override `udsPath` with a path under an existing directory, you own that parent
directory's permissions. Spectre creates the default per-attach directory and socket owner-only —
mode 0700/0600 on POSIX, an owner-only ACL (owner full control, inherited ACEs dropped) on
Windows — but it does not tighten directories it did not create.

`AgentAttach.attach` runs a **same-user preflight** via `ProcessHandle` and throws
`AttachPermissionDeniedException` if the target JVM is owned by a different OS user (the
JDK Attach API only works across attach-compatible same-user processes on POSIX).

The JEP 451 `-XX:+EnableDynamicAgentLoading` flag is **not** verified by Spectre yet — the
JVM itself prints a stderr warning if it's missing, which is the source of truth. A follow-up
can add a reliable preflight via `HotSpotDiagnosticMXBean`.

## Operation set

`AttachedAutomator` exposes the same operations as the HTTP transport, plus `detach`:

| Method                | Wire op                          | Returns           |
|-----------------------|----------------------------------|-------------------|
| `windows()`           | `AgentRequest.Windows`           | `List<WindowSummaryDto>` |
| `allNodes()`          | `AgentRequest.AllNodes`          | `List<NodeSnapshotDto>`  |
| `findByTestTag(tag)`  | `AgentRequest.FindByTestTag`     | `List<NodeSnapshotDto>`  |
| `click(nodeKey)`      | `AgentRequest.Click`             | `Unit`            |
| `typeText(text)`      | `AgentRequest.TypeText`          | `Unit`            |
| `screenshot(windowIndex?, surfaceId?, fullscreen?)` | `AgentRequest.Screenshot` | `ByteArray` (PNG); default window index 0, not full desktop |
| `capture(windowIndex)`| `AgentRequest.Capture`           | `AtomicCaptureResult` |
| `windowIdentities(windowIndex?)` | `AgentRequest.WindowIdentity` | `List<WindowIdentityDto>` |
| `close()` (auto)      | `AgentRequest.Detach`            | tear-down         |

`windowIdentities` returns native handle/id (when resolvable), window and Compose-surface
bounds in **AWT user-space screen coordinates** (same space as `windows()` /
`locationOnScreen` / Robot), surface bounds **relative to the window** (crop rect),
per-window affine transform (`scaleX`/`scaleY`/`translateX`/`translateY`), and a
`cropRequired` flag when the surface is a subset of the top-level window (title bar or
embedded panel). For device pixels: point `(x, y) → (x * scaleX + translateX, y * scaleY +
translateY)`; scale widths/heights by `scaleX`/`scaleY` only (no translation). Daemon-owned
recording (#183) uses this so capture stays on the daemon host rather than over the
transport.

Streaming / long-poll ops (`waitForVisualIdle`, idling resources, `withTracing`) are
deferred to a follow-up.

## Wire format

Length-prefixed CBOR over the UDS:

```
[4-byte big-endian length][N bytes CBOR-encoded AgentRequest|AgentResponse]
```

DTOs live in `dev.sebastiano.spectre.agent.transport.*`. Both sides share the same
classes; CBOR's `@SerialName` discriminators pin each variant in the sealed-interface
hierarchy.

## Current limitations

- **Windows needs 10 version 1803 / Server 2019 or newer.** That's when native `AF_UNIX` landed;
  older Windows fails the attach preflight with `AttachPlatformUnsupportedException`.
- **No streaming ops.** `waitForVisualIdle` and friends are HTTP-only or in-process only for now.
- **IntelliJ-hosted Compose**: the classloader-disambiguation rule (D-14 in the plan) was
  designed to handle `PluginClassLoader` chains but isn't automatically tested yet. If
  you hit issues attaching to an IntelliJ-hosted target, file a Spectre issue with the
  `agent-attach` label.
- **Runtime jar is separate from the API jar.** The normal `spectre-agent` dependency is not the
  jar loaded into the target JVM. Add `spectre-agent-runtime`, pass
  `AttachOptions.agentJarPath`, or set `-Ddev.sebastiano.spectre.agent.runtimeJar=...`.

## Manual verification recipe

```bash
# Terminal A — start a Spectre-instrumented app
./gradlew :sample-desktop:run

# Find its PID (cross-platform: jps ships with the JDK)
jps -l | grep "dev.sebastiano.spectre.sample.MainKt"
# POSIX alternative: ps -A | grep "…MainKt" | awk '{print $1}'

# Terminal B — attach the agent. The agent's stderr lands in Terminal A.
./gradlew :agent:attachSpike -Ppid=<pid>
```

The `attachSpike` task is intentionally separate from `:check` — it exists for human
verification and is not config-cache compatible.

## CLI and MCP

The `spectre` executable is a client for a per-user local daemon. It starts that daemon on
demand, and `spectre mcp` shares the same daemon and its attached sessions with ordinary CLI
commands. Keep the executable running only through the MCP client: MCP uses its standard input
and output for protocol frames, so do not add a shell wrapper that prints banners to standard
output.

Start with the same target prerequisites described above, then find and attach it from a shell:

```bash
spectre ps --json
spectre attach <pid> --json
```

The attach response contains an `id`. Pass it to commands such as `tree`, `find`, `click`, and
`screenshot`. `screenshot` writes a PNG of the attached session's tracked window (default index
`0`) to `--output`; without that option it creates a temporary file and prints its path. Use
`--window`, `--surface`, or opt-in `--fullscreen` as described in [CLI](cli.md).

### Claude Code recipe

Install the `spectre` executable where Claude Code can invoke it, then add it to the project's
`.mcp.json`. Use an absolute path so Claude Code does not depend on your interactive shell's
`PATH`:

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

Restart Claude Code after changing the configuration. It can then use these tools in order:

1. `list_processes` to find the target PID.
2. `attach` with that PID and retain the returned `sessionId`.
3. `tree` or `find` to retrieve current node keys, then `click` or `type_text` to interact.
4. `screenshot` to receive a window-scoped PNG as MCP image content (optional `window_index` /
   `surface_id` / `fullscreen`), rather than a file path.

Node keys are short-lived: get a fresh key with `tree` or `find` after an interaction changes the
UI. Use `spectre daemon kill` to stop the shared daemon and discard its sessions when you are
finished.

# Agent attach (experimental)

Spectre's `:agent` module lets you attach to a **running**, Spectre-instrumented JVM and
drive its UI from a separate process — no need to mount routes at startup, no HTTP, no
network listener.

This is the right transport when:

- Your test JVM and the UI JVM are different processes by design, but you don't want to
  modify the UI app's startup wiring.
- You want to inspect a long-running Spectre-aware app interactively (the future
  `spectre attach <pid>` CLI builds on this surface).
- You're driving an IntelliJ-hosted Compose surface from a sister process. *Note: see the
  current limitations below — IntelliJ support is gated until further validation.*

For comparison with the other transports, see [Cross-JVM access](cross-jvm.md) (HTTP) and
[IntelliJ-hosted Compose](intellij.md) (in-process via `intellij-ide-starter`).

!!! warning "Experimental API"

    Everything under `dev.sebastiano.spectre.agent.*` is annotated
    `@ExperimentalSpectreAgentApi` and requires explicit opt-in. The API may change in any
    release until the UX stabilizes. See [Stability policy](../STABILITY.md).

!!! warning "Trust boundary"

    The agent transport is **local only** and intended for **trusted dev/test
    environments**. Trust model:

    - Communication is over a **Unix Domain Socket** under a short private directory in `/tmp/`.
      Filesystem permissions (directory mode 0700, socket mode 0600) are the only access control.
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
- **macOS and Linux only** in the current preview. Windows support is tracked as a follow-up (named pipes
  via JNA or junixsocket).
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
    implementation("dev.sebastiano.spectre:core:<version>")
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

`AgentAttach` auto-discovers `spectre-agent-runtime-<version>.jar` from the attacher's runtime
classpath. In practice, `runtimeOnly(...)` makes Gradle launch the attacher with the runtime jar
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

Classpath auto-discovery is the default, but it only works when the runtime jar is visible as a
physical `spectre-agent-runtime-<version>.jar` entry in the attacher's `java.class.path`. Custom
launchers, shaded tools, module-path launches, and ad-hoc scripts may hide that file. In those
cases, point Spectre at the runtime jar explicitly:

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
    udsPath = null,             // null = /tmp/sp-a-<pid>-<8char-uuid>/agent.sock
    attachTimeoutMs = 5_000,    // how long to wait for the agent's IPC server to come up
)
```

If you override `udsPath` with a path under an existing directory, you own that parent
directory's permissions. Spectre creates the default per-attach directory as mode 0700 and the
socket as mode 0600, but it does not chmod directories it did not create.

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
| `screenshot()`        | `AgentRequest.Screenshot`        | `ByteArray` (PNG) |
| `close()` (auto)      | `AgentRequest.Detach`            | tear-down         |

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

- **macOS and Linux only.** Windows support is a tracked follow-up.
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

# Find its PID
ps -A | grep "dev.sebastiano.spectre.sample.MainKt" | awk '{print $1}'

# Terminal B — attach the agent. The agent's stderr lands in Terminal A.
./gradlew :agent:attachSpike -Ppid=<pid>
```

The `attachSpike` task is intentionally separate from `:check` — it exists for human
verification and is not config-cache compatible.

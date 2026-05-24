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
  v1 limitations below — IntelliJ support is gated until further validation.*

For comparison with the other transports, see [Cross-JVM access](cross-jvm.md) (HTTP) and
[IntelliJ-hosted Compose](intellij.md) (in-process via `intellij-ide-starter`).

!!! warning "Experimental API"

    Everything under `dev.sebastiano.spectre.agent.*` is annotated
    `@ExperimentalSpectreAgentApi` and requires explicit opt-in. The API may change in any
    release until the UX stabilizes. See [Stability policy](../STABILITY.md).

!!! warning "Trust boundary"

    The agent transport is **local only** and intended for **trusted dev/test
    environments**. Trust model:

    - Communication is over a **Unix Domain Socket** in `/tmp/`. Filesystem permissions
      (mode 0600, owner-only) are the only access control.
    - The attaching JVM must run as the same OS user as the target JVM.
    - There is **no authentication** and **no encryption** on the wire.
    - The agent module is **unpublished in v1** — no Maven Central, no `mavenLocal`.
      Consume via a direct project dependency or an explicit `AttachOptions.agentJarPath`.
      See [Target setup](#target-setup) below.

    See [Security notes](../SECURITY.md) for the full risk register.

## Requirements

- **JDK 21+** on both the attaching and target JVMs.
- The attaching JVM must be a **JDK** (not a JRE) with the `jdk.attach` module on the
  module graph.
- The target JVM must include **Spectre `:core`** on its classpath. The agent does not
  inject Spectre into the target; it reflectively bootstraps off the `:core` that's
  already loaded. The agent JAR itself is supplied by the attaching JVM at attach time.
- **macOS and Linux only** in v1. Windows support is tracked as a follow-up (named pipes
  via JNA or junixsocket).
- The target JVM should be started with **`-XX:+EnableDynamicAgentLoading`**. Without it,
  attach prints a stderr warning per
  [JEP 451](https://openjdk.org/jeps/451) and a future JDK will reject the attach
  entirely.

## Target setup

The agent JAR is a thin bootstrap. It reflectively locates `ComposeAutomator` in the
target's classloader at attach time, so the target **must already have Spectre `:core`
on its classpath** — typically as a normal runtime dependency. The agent JAR doesn't
need to be on the target's classpath; it gets loaded via `VirtualMachine.loadAgent`.

```kotlin
// build.gradle.kts of the target application
dependencies {
    implementation("dev.sebastiano.spectre:core:<version>")
    // No `:agent` dependency needed in the target. The agent fat JAR is supplied by the
    // attaching JVM (the test/inspector) and loaded into this process at attach time.
}
```

**`:agent` is not published to Maven Central or `mavenLocal` in v1.** The module
deliberately doesn't apply `mavenPublish` — see plan Q-1 (track Central publishing as a
v1.1 follow-up). Today, attaching-side consumers have two supported paths:

1. **As a project dependency** (you're inside the Spectre repo or a Gradle composite
   build that includes it):

    ```kotlin
    // build.gradle.kts of the test/attacher module
    dependencies {
        implementation(projects.agent)
    }
    ```

2. **As a path to the fat agent JAR** — useful when the attacher and target are wired
   up out-of-band (e.g. CI scripts, ad-hoc inspector tools). Build the JAR once with
   `./gradlew :agent:shadowJar` (output at `agent/build/libs/agent-*-all.jar`) and tell
   `AgentAttach` where to find it via:

    ```kotlin
    AgentAttach.attach(
        pid = targetPid,
        options = AttachOptions(agentJarPath = Path.of("/abs/path/to/agent-*-all.jar")),
    )
    ```

    Equivalent: set `-Ddev.sebastiano.spectre.agent.runtimeJar=<path>` on the attacher's
    JVM. Spectre's own `:agent:test` task uses this property to point integration tests
    at the freshly-built shadow JAR.

If neither is set explicitly, `AgentAttach` falls back to scanning
`<cwd>/agent/build/libs/agent-*-all.jar` — convenient for in-repo manual recipes only.

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
    agentJarPath = null,        // null = auto-locate (see "Target setup" above)
    udsPath = null,             // null = /tmp/sp-a-<pid>-<8char-uuid>.sock
    attachTimeoutMs = 5_000,    // how long to wait for the agent's IPC server to come up
)
```

`AgentAttach.attach` runs a **same-UID preflight** via `ProcessHandle` and throws
`AttachPermissionDeniedException` if the target JVM is owned by a different OS user (the
JDK Attach API only works across same-UID processes on POSIX).

The JEP 451 `-XX:+EnableDynamicAgentLoading` flag is **not** verified by Spectre in v1 —
the JVM itself prints a stderr warning if it's missing, which is the source of truth.
v1.1 will add a reliable preflight via `HotSpotDiagnosticMXBean`.

## Operation set (v1)

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
deferred to v1.1.

## Wire format

Length-prefixed CBOR over the UDS:

```
[4-byte big-endian length][N bytes CBOR-encoded AgentRequest|AgentResponse]
```

DTOs live in `dev.sebastiano.spectre.agent.transport.*`. Both sides share the same
classes; CBOR's `@SerialName` discriminators pin each variant in the sealed-interface
hierarchy.

## v1 limitations

- **macOS and Linux only.** Windows support is a tracked follow-up.
- **No streaming ops.** `waitForVisualIdle` and friends are HTTP-only or in-process only
  until v1.1.
- **IntelliJ-hosted Compose**: the classloader-disambiguation rule (D-14 in the plan) was
  designed to handle `PluginClassLoader` chains but isn't automatically tested in v1. If
  you hit issues attaching to an IntelliJ-hosted target, file a Spectre issue with the
  `agent-attach` label.
- **Unpublished — no Maven Central, no `mavenLocal`.** `:agent` doesn't apply
  `mavenPublish` in v1. Attaching-side consumers use a direct project dependency
  (`implementation(projects.agent)`) or point at the built fat JAR via
  `AttachOptions.agentJarPath` / `-Ddev.sebastiano.spectre.agent.runtimeJar=...`. See
  [Target setup](#target-setup) above.

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

# Agent attach (experimental)

The `:agent` module controls a **running Compose Desktop JVM** from another process.
The target does not need an HTTP listener, network port, or startup route registration.

Attach uses one API with two target setups:

1. **Preinstalled `spectre-core` is preferred.** Bootstrap finds `ComposeAutomator` on the
   target classpath. Use this setup for a controlled target build.
2. **Inject works without preinstalled core.** The `spectre-agent-runtime` jar contains
   `META-INF/spectre/inject-runtime.jar`. Bootstrap loads core from that payload when the
   target has Compose and Skiko. The call remains `AgentAttach.attach(pid)`.
   Use inject for occasional inspection, such as a stock IntelliJ attach.
   Preinstall core for sustained or frequent sessions.
   Details: [Injection without preinstalled core](#injection-without-preinstalled-core).

Use agent attach when:

- The test JVM and UI JVM are separate processes, and the UI app's startup wiring must remain unchanged.
- A long-running Compose Desktop app needs interactive inspection through the
  `spectre` CLI or an MCP client.
- An IntelliJ-hosted Compose surface needs attach from a sister process (often via
  inject when the IDE build does not ship Spectre). See [IntelliJ-hosted Compose](intellij.md)
  for VM options and the stock-IDE recipe.

See [Cross-JVM access](cross-jvm.md) for HTTP and [IntelliJ-hosted Compose](intellij.md)
for in-process automation through `intellij-ide-starter`.
The [capability matrix](capability-matrix.md) records supported operations, unsupported
operations, and cases without CI execution. Each supported case needs executable CI evidence.

!!! warning "Experimental API"

    Everything under `dev.sebastiano.spectre.agent.*` is annotated
    `@ExperimentalSpectreAgentApi` and requires explicit opt-in. The API may change in any
    release until the UX stabilises. See [Stability policy](../STABILITY.md).

!!! warning "Trust boundary"

    The agent transport is **local only** and intended for **trusted dev/test
    environments**. Trust model:

    - Communication is over a **Unix Domain Socket** under a short private directory (`/tmp/` on
      Linux/macOS, `%TEMP%` on Windows). Filesystem permissions are the only access control:
      directory mode 0700 / socket mode 0600 on POSIX, or an owner-only ACL on Windows/NTFS.
    - The attaching JVM must run as the same OS user as the target JVM.
    - There is **no authentication** and **no encryption** on the wire.
    - The published `spectre-agent` API jar is for the attaching JVM. The
      `spectre-agent-runtime` jar gets loaded into the target JVM. See
      [Artifact roles](#artifact-roles) below.

    See [Security notes](../SECURITY.md) for the full risk register.

## Requirements

- **JDK 21+** on both the attaching and target JVMs.
- The attaching JVM must be a **JDK** with the `jdk.attach` module on the
  module graph.
- The target JVM needs a **Compose Desktop host** (so Spectre can find semantics owners).
  Prefer a **preinstalled** `spectre-core` dependency on the target for production-style
  attach. If core is absent, the agent runtime can **inject** a nested
  `META-INF/spectre/inject-runtime.jar` payload (experimental inspect path; see
  [Injection without preinstalled core](#injection-without-preinstalled-core)).
- **Linux, macOS, and Windows.** The transport uses native Unix Domain Sockets (`AF_UNIX`) on all
  three. It uses no named pipes or extra dependencies. Windows requires **Windows 10 version 1803 /
  Windows Server 2019 or newer**, when native `AF_UNIX` landed; older Windows fails the attach
  preflight with a clear message.
- The target JVM should be started with **`-XX:+EnableDynamicAgentLoading`**. Without it,
  attach prints a stderr warning per
  [JEP 451](https://openjdk.org/jeps/451) and a future JDK will reject the attach
  entirely. Spectre's launch harness adds the flag for processes it starts; stock apps and
  IDEs need it in their own VM options (see [IntelliJ-hosted Compose](intellij.md#external-attach-without-spectre-core)).

## Artifact roles

Agent attach uses two JVMs:

- **Target JVM** — the Compose app under inspection or automation.
- **Attacher JVM** — the test, inspector, or tool process that calls `AgentAttach.attach(pid)`.

Preinstall Spectre core for a controlled target build. Bootstrap then uses the
instrumented path without an inject classloader:

```kotlin
// build.gradle.kts of the target application
dependencies {
    implementation("dev.sebastiano.spectre:spectre-core:<version>")
    // No `spectre-agent` or `spectre-agent-runtime` dependency is needed in the target.
    // The attacher supplies the runtime jar to the JDK Attach API.
}
```

For a stock IDE or third-party binary, attach can inject core when the target has Compose
and the runtime jar contains the nested payload.

The attacher JVM normally needs these artefacts:

- `spectre-agent` — the normal API jar used to compile test/inspector code.
- `spectre-agent-runtime` — the loadable Java-agent runtime jar that gets passed to
  `VirtualMachine.loadAgent(...)`.

Declare the API dependency for compilation and the agent jar for runtime loading:

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
4. In-repo fallback at `<spectre-checkout>/agent-runtime/build/libs/agent-runtime-*.jar`,
   **only when the attacher cwd is inside a Spectre source checkout** (detected via monorepo
   markers). Published consumers should use options 1–3; the fallback is a Spectre-dev convenience.

With `runtimeOnly(...)`, Gradle lists the runtime jar in the attacher's `java.class.path`.
Spectre passes that physical path to `VirtualMachine.loadAgent(...)`.
Attacher code does not call runtime classes directly. The target does not declare
`spectre-agent-runtime` as a dependency.

The attacher also has an input coordination override:
`-Ddev.sebastiano.spectre.agent.inputCoordination=disabled` (or
`AttachOptions.inputCoordination = AttachInputCoordination.Disabled`) drops the attach path from
`InputLeasePolicy.Required` to `Off` in the target. It exists so a broken input coordinator is
recoverable. Disabling it removes the guarantee that no other Spectre process is
driving the same desktop. Read
[When the coordinator cannot be reached](input-coordination.md#coordinator-unreachable) first.

Classpath and directory discovery require exactly one runtime jar.
If multiple `spectre-agent-runtime-*.jar` or `agent-runtime-*.jar` files match,
`AmbiguousAgentRuntimeJarException` lists them. Set `AttachOptions.agentJarPath` or
`-Ddev.sebastiano.spectre.agent.runtimeJar` to choose one explicitly.

## How attach works

`AgentAttach.attach(pid)` performs this sequence:

1. Pick a fresh Unix Domain Socket path such as `/tmp/sp-a-<pid>-<8char-uuid>/agent.sock`, and
   check that it fits the platform's `sun_path` limit.
2. Resolve the loadable `spectre-agent-runtime-<version>.jar`.
3. Run attach preflights, including the same-OS-user check.
4. Call `VirtualMachine.attach(pid).loadAgent(runtimeJarPath, udsPath)`.
5. The target JVM loads the runtime jar and invokes `SpectreAgent.agentmain(...)`.
6. Inside the target JVM, bootstrap locates or injects `ComposeAutomator` (see below), creates
   an in-process automator, and starts an IPC server on the UDS path.
7. The attacher connects an `IpcClient` to that socket and returns `AttachedAutomator`.

After attach, methods such as `windows()`, `findByTestTag(...)`, `click(...)`, and
`screenshot()` send CBOR requests through the socket. The target runs them against its
in-process automator and returns DTOs or bytes.

The attacher makes a best-effort attempt to start the experimental input coordinator.
Read-only attach still works if startup fails. Current core selects
`InputLeasePolicy.Required` on `RobotDriver.synthetic()`.
Clipboard paste, and real OS input when enabled, then fail if coordination remains unavailable.
An older preinstalled core without `InputLeasePolicy` or `synthetic()` uses the legacy
no-argument `RobotDriver` without coordination.

### Injection without preinstalled core

Bootstrap order inside the target:

1. Prefer a **preinstalled** `ComposeAutomator` already on the target classpath
   (instrumented attach).
2. Else extract the nested **`META-INF/spectre/inject-runtime.jar`** from the agent runtime
   jar, open a child classloader parented at a Compose host loader, and load Spectre core
   from that payload (inject attach). Compose / Skiko stay on the target; only Spectre core
   and relocated kotlinx bits come from the inject jar.

Inject is an **experimental inspection** path for occasional attach, inspect, and detach
sessions. Use preinstalled core for frequent CI attach loops.
Class unloading after detach depends on garbage collection; inject has no leak-free
production guarantee.

Both paths use `AgentAttach.attach(pid)`. The public API has no inject flag.

## Custom runtime jar path

Normal Gradle runs use classpath discovery. `AttachOptions.agentJarPath` and
`-Ddev.sebastiano.spectre.agent.runtimeJar=<path>` take precedence over that scan.
Use an explicit path when a launcher, shaded tool, module-path launch, or script hides
the physical runtime jar from `java.class.path`.

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

Inside a Spectre checkout, `AgentAttach` can find
`<checkout>/agent-runtime/build/libs/agent-runtime-*.jar` after
`./gradlew :agent-runtime:jar`. This fallback also works in checkout subdirectories.
Outside the checkout, put `spectre-agent-runtime` on the attacher classpath or supply
an explicit path or system property.

Consumers that cannot use the published Maven coordinate still have two supported paths:

1. **As a project dependency** (inside the Spectre repo or a Gradle composite
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
    // Window/surface attach screenshots fail closed (#359); fullscreen is the only
    // screen-pixel capture mode on this path.
    val pngBytes = automator.screenshot(fullscreen = true)
} // detach + cleanup on close()
```

`AttachedAutomator` implements `AutoCloseable`. `close()` sends `AgentRequest.Detach`.
The agent stops accepting requests, closes its input coordinator session, releases
`ComposeAutomator`, removes the UDS path, and unregisters its shutdown hook.
A target-side shutdown hook handles crash cleanup.

### `AttachOptions`

```kotlin
AttachOptions(
    agentJarPath = null,        // null = auto-locate (see "Artifact roles" above)
    udsPath = null,             // null = <base>/sp-a-<pid>-<8char-uuid>/agent.sock (see below)
    attachTimeoutMs = 5_000,    // how long to wait for the agent's IPC server to come up
)
```

The default `<base>` is `/tmp` on Linux and macOS. Windows uses `%TEMP%` or,
if that path is too deep, `%LOCALAPPDATA%\Temp`. The `sockaddr_un.sun_path`
limit is 102 usable bytes on macOS and 106 on Linux and Windows. Custom paths
must fit. `attach` throws `UdsPathTooLongException` before loading the agent
when a path exceeds the limit. The error names the path and limit.

When setting `udsPath` under an existing directory, secure that parent directory.
Spectre creates its default per-attach directory and socket for the owner only.
It uses mode 0700/0600 on POSIX. On Windows, it gives the owner full control and
drops inherited ACL entries. Spectre leaves existing parent permissions unchanged.

`AgentAttach.attach` runs a **same-user preflight** and throws
`AttachPermissionDeniedException` if the target JVM is owned by a different OS user (the
JDK Attach API only works across attach-compatible same-user processes on POSIX). On
Linux/macOS the preflight prefers numeric UID equality when both sides can be resolved, and
falls back to `ProcessHandle` usernames when UID lookup is unavailable (#166).

The JEP 451 `-XX:+EnableDynamicAgentLoading` flag is **not** verified by Spectre yet.
The JVM itself prints a stderr warning if it's missing, which is the source of truth. A follow-up
can add a reliable preflight via `HotSpotDiagnosticMXBean`.

## Operation set

`AttachedAutomator` exposes the same operations as the HTTP transport, plus `detach`:

| Method                | Wire op                          | Returns           |
|-----------------------|----------------------------------|-------------------|
| `windows()`           | `AgentRequest.Windows`           | `List<WindowSummaryDto>` (includes `isShowing`; delayed-show hosts may appear before they are visible so keys agree with `allNodes()` — #362) |
| `allNodes()`          | `AgentRequest.AllNodes`          | `List<NodeSnapshotDto>`  |
| `findByTestTag(tag)`  | `AgentRequest.FindByTestTag`     | `List<NodeSnapshotDto>`  |
| `click(nodeKey)`      | `AgentRequest.Click`             | `Unit`            |
| `doubleClick(nodeKey)` | `AgentRequest.DoubleClick`     | `Unit`            |
| `longClick(nodeKey, holdForMs?)` | `AgentRequest.LongClick` | `Unit`            |
| `swipe(...)`          | `AgentRequest.Swipe`             | `Unit` (node-to-node or screen coords) |
| `scrollWheel(nodeKey, wheelClicks)` | `AgentRequest.ScrollWheel` | `Unit`       |
| `pressKey(keyCode, modifiers?)` | `AgentRequest.PressKey`  | `Unit`            |
| `focusWindow(nodeKey)` | `AgentRequest.FocusWindow` | `Unit` (raise/activate window hosting node) |
| `typeText(text)`      | `AgentRequest.TypeText`          | `Unit`            |
| `screenshot(windowIndex?, surfaceId?, fullscreen?)` | `AgentRequest.Screenshot` | `ByteArray` (PNG); **only `fullscreen=true` succeeds** on attach — window/surface fail closed (#359) |
| `capture(windowIndex)`| `AgentRequest.Capture`           | `AtomicCaptureResult` |
| `windowIdentities(windowIndex?)` | `AgentRequest.WindowIdentity` | `List<WindowIdentityDto>` |
| `waitForNode(...)`    | `AgentRequest.WaitForNode`       | `NodeSnapshotDto` |
| `waitUntilGone(...)`  | `AgentRequest.WaitUntilGone`     | `Unit` (absence wait — #438; timeout error keeps the selector, timeout, and still-present count) |
| `waitForVisualIdle(...)` | `AgentRequest.WaitForVisualIdle` | `Unit`         |
| `waitForIdle(...)`    | `AgentRequest.WaitForIdle`       | `Unit` (fingerprint wait; no idling-resource registration over attach — #362) |
| `printTree()`         | `AgentRequest.PrintTree`         | `String` (human-readable dump — #362) |
| `screenshot(node)`    | `AgentRequest.Screenshot(nodeKey)` | `ByteArray` (PNG of node bounds — #362; native when recording bridge present, else region of `boundsOnScreen`) |
| `click(node)`         | `AgentRequest.Click`             | `Unit` (DTO overload uses [NodeSnapshotDto.key] — #362) |
| `close()` (auto)      | `AgentRequest.Detach`            | tear-down         |

`windowIdentities` returns a native handle or ID when it can resolve one.
It also returns window and Compose-surface bounds in **AWT user-space screen coordinates**.
These match `windows()`, `locationOnScreen`, and Robot coordinates.
Surface bounds relative to the window supply a crop rectangle.
The result includes `scaleX`, `scaleY`, `translateX`, `translateY`, and `cropRequired`.
`cropRequired` is true when the Compose surface occupies only part of the top-level window.
For device pixels, transform a point as
`(x, y) → (x * scaleX + translateX, y * scaleY + translateY)`.
Scale widths and heights without translation. Daemon-owned recording uses this metadata
to capture on the daemon host.

`waitForNode`, `waitForVisualIdle` (#201), `waitForIdle` (#362), and `waitUntilGone` (#438)
are available over the agent transport. `waitForIdle` runs a **fingerprint-only** wait on the
agent's in-target automator (timeout / quiet / poll; absolute deadline on the wire like
`waitForNode`). It does **not** observe idling resources registered on a different automator
instance in the app. **Idling-resource registration** and `withTracing` remain in-process-only.

Each wait returns its **timeout message verbatim** with category `timeout`.
`waitForIdle` names busy idling resources. `waitForVisualIdle` reports frame sample counts.
`waitUntilGone` names the selector and the remaining match count.

The wire deadline is the wait budget **plus one second**.
The agent checks it with a scheduler and after the handler returns.
An identical deadline could replace the automator's timeout detail with
`Deadline elapsed during op`. The added second lets the automator report its cause.
The agent's `timeout + 2s` suspend-bridge limit still bounds a stalled operation.

Agent, HTTP, and daemon/CLI/MCP support `doubleClick`, `longClick`, `swipe`,
`scrollWheel`, and `pressKey` (#203). Over attach, `focusWindow(nodeKey)` raises
and focuses the node's AWT window (#364). Call it before `pressKey` or `typeText`
when the attach client holds foreground focus.

## Wire format

Length-prefixed CBOR over the UDS:

```
[4-byte big-endian length][N bytes CBOR-encoded AgentRequest|AgentResponse]
```

DTOs live in `dev.sebastiano.spectre.agent.transport.*`. Both sides share the same
classes; CBOR's `@SerialName` discriminators pin each variant in the sealed-interface
hierarchy.

### Protocol version handshake (#199)

After the UDS connects, the first exchange is always:

1. Client → `hello` with `protocolVersion` (currently `3` — `ProtocolVersion.CURRENT`)
2. Runtime → `helloAck` with the same version, or `error` with category
   `protocolMismatch`

The experimental agent protocol requires an **exact version match**.
A mismatch fails attach with `IOException` or `SpectreAgentException`.
A future additive compatibility range would change `ProtocolVersion.CURRENT` and this guide.

The protocol has three revisions. **v1** uses bare request and response frames after
`hello`. **v2** (#200) adds operation envelopes with op ids, cancellation, and deadline
budgets. **v3** encodes bulk `pngBytes` and `captureJsonUtf8` payloads as CBOR byte
strings. Earlier peers expect integer arrays; the type system cannot detect this
representation change. Peers therefore reject an incompatible version at `hello`,
before a screenshot frame arrives.

### Unknown operations

If the runtime receives an unknown sealed `@SerialName` request, it returns an `error`
with category **`unsupportedOperation`**. This gives mixed-version peers a defined failure.

### Error taxonomy

`AgentResponse.Error` carries a stable `category` string alongside `message`:

| Category | Meaning |
| --- | --- |
| `unsupportedOperation` | Runtime too old / op not implemented |
| `protocolMismatch` | Handshake or schema/framing mismatch |
| `invalidSelector` | Malformed node key or selector |
| `nodeNotFound` | Well-formed key, no matching node |
| `timeout` | Deadline exceeded |
| `cancelled` | Explicit cancel of an in-flight op (#200) |
| `payloadTooLarge` | Response/request exceeds the frame hard limit (#204) |
| `inputRejected` | Focus / Robot / permission rejection |
| `internalError` | Unexpected agent-side failure (default) |

### Selectors (#202)

Beyond `findByTestTag`, the agent transport supports:

| Wire op | Notes |
| --- | --- |
| `findByText` | `text`, `exact` (default true) |
| `findByContentDescription` | `description` |
| `findByRole` | `role` string (e.g. `Button`); matches `role.toString()` |

`NodeSnapshotDto` includes `contentDescriptions`, `isDisabled`, `isSelected` (HTTP field-set
parity). On-screen `bounds` stay integer AWT units on the agent; HTTP keeps double
window+screen rects.

### Payload limits (#204)

Each IPC frame is length-prefixed, with **two** separate bounds:

| Bound | Value | Applies to |
|---|---|---|
| Write budget | **64 MiB** default (`DEFAULT_MAX_FRAME_BYTES`), configurable | the payload a process will send |
| Read ceiling | **512 MiB** fixed (`MAX_FRAME_BYTES_CEILING`) | the length a process will accept from a header |

The read ceiling limits memory allocation from an invalid or desynchronised frame header.
`readFrame` allocates a buffer based on that header length.
The ceiling stays fixed above every allowed write budget, so a reader accepts any
legitimately configured writer's frame.

The default write budget covers screenshots. An incompressible 3840×2160 24-bit sRGB
image approaches 25 MB. The 64 MiB budget leaves room for a dual-4K desktop.

`pngBytes` and `captureJsonUtf8` use `@ByteString`, so CBOR encodes them as byte strings.
Without that annotation, kotlinx serialises each signed byte as an integer.
That can grow a frame to about **1.8×** its payload and invalidate the stated budgets.
`WirePayloadEncodingTest` checks the encoding.

#### Raising the budget

Larger multi-monitor HiDPI rigs (dual-5K and up) can exceed the default. Raise it with either:

```shell
export SPECTRE_MAX_FRAME_BYTES=256MiB   # bytes, or a binary suffix: 512, 128k, 64M, 64MiB, 1G
spectre --max-frame-bytes 256MiB capture <session-id>
```

The flag takes precedence over the environment variable.
Both accept values up to the 512 MiB read ceiling.

Propagation matters, because three processes are involved:

- The **CLI** applies the value to itself.
- A **daemon this invocation starts** inherits it on its command line. A daemon that is *already
  running* keeps the budget it booted with and reports it in the handshake. Asking for a
  different one **fails loudly**. It cannot partially apply the request:

  ```text
  Cannot honour --max-frame-bytes=256MiB (268435456 bytes): the running Spectre daemon started
  with 64MiB (67108864 bytes), and a daemon keeps the budget it booted with. Run
  `spectre daemon kill` and retry so the new budget applies to the daemon and to the JVMs it
  injects.
  ```

  Only an explicit request is refused, and *explicit* means the option or the variable was
  present, regardless of whether its value differs from the default. Asking for `64MiB`
  against a `128MiB` daemon is a conflict like any other. Leaving the budget alone asks for nothing, so a daemon
  running something larger is accepted silently: readers take frames up to the ceiling whatever
  their own budget. A daemon too old to report its budget cannot have been started with the
  requested one, so the request is refused because compatibility cannot be established.
- The **injected agent** cannot read the daemon's environment, and it is the process that writes
  the bulky screenshot frames, so the daemon forwards its resolved budget in `agentArgs` (see
  below). It applies before the agent's IPC server accepts a request.

Readers accept frames up to the ceiling. The mismatch error rejects a requested budget
that the running daemon cannot apply.

#### Over-budget behaviour

Frames over the write budget **fail closed**:

- Responses that encode larger than the budget are **not** truncated or spilled to disk by the
  agent transport. The runtime replies with `error` category **`payloadTooLarge`** and a message
  that includes the sizes.
- The connection stays open; subsequent ops on the same session continue normally.
- Parity CI can rely on deterministic taxonomy behaviour without size-threshold flakes.

The **fullscreen still** can first use logical resolution when its original size exceeds
the budget. It reports `payloadTooLarge` if that image still exceeds the limit. See
[Atomic capture — Pixel scale](capture.md#pixel-scale).

Capture directories and a shared daemon filesystem can handle larger artefacts.
The wire protocol has no separate spill-to-file path for oversized frames.

#### `agentArgs` format

`-javaagent:spectre-agent-runtime.jar=<agentArgs>` accepts two forms:

| Form | Example | Used by |
|---|---|---|
| Bare UDS path | `/tmp/sp-a-123-abcd/agent.sock` | hand-written `-javaagent:` lines; still **accepted** |
| Structured | `uds=/tmp/sp-a-123-abcd/agent.sock,maxFrameBytes=268435456` | everything Spectre **emits** |

Spectre recognises the structured form when arguments start with `uds=` and include
another comma-separated field. It percent-escapes emitted values, preserving `,` and
`=` in UDS paths. A manually written bare path such as `uds=agent,1.sock` is
ambiguous and reads as structured. Spectre emits only structured arguments. Bare paths
remain accepted for manually written `-javaagent:` lines.

The runtime ignores unknown keys and values it cannot parse.
A runtime that predates structured arguments treats the entire string as a socket path,
so attach times out. This pairing requires an explicit runtime jar override through
`AttachOptions.agentJarPath` or the system property.
The bootstrap timeout identifies that possibility. Changes to payload representation bump
`ProtocolVersion.CURRENT` so mixed peers fail at the handshake.

Spectre always emits the frame budget, including its default. A target can also set
`SPECTRE_MAX_FRAME_BYTES`. Explicitly sending the budget keeps the daemon and injected
JVM aligned. For example, a 16 MiB target limit would reject captures that a
64 MiB daemon could otherwise carry.

HTTP maps `payloadTooLarge` → **413 Payload Too Large**.

### Long operations and cancel (#200)

After Hello, each request carries `opId`, an optional absolute `deadlineEpochMs`,
and its body. Responses use `opId` to match requests on a shared connection.

- Long work (future waits, heavy capture) runs on a **worker thread** so the accept loop
  remains responsive to cancel/detach.
- **Cancel** is an explicit wire op (`cancel` with the target `opId`). Closing the socket
  has different lifecycle semantics.
- Closing a CLI/MCP front-end during a long-poll cancels only that **front-end** connection's
  in-flight work. The **daemon session stays attached** until an explicit `detach` (or daemon
  kill / crash). Reconnect or use the CLI against the same session id. A disconnect leaves
  the daemon session attached and recoverable (see lifecycle below). The agent transport
  itself only detaches on an explicit `detach` or handshake
  failure teardown.


Clients should use `category` from `AgentErrorCategory` or `SpectreAgentException`.
The free-text `message` explains the error. HTTP maps categories to status codes
(`invalidSelector` → 400, `nodeNotFound` → 404, `unsupportedOperation` → 501,
`cancelled` → 499 Client Closed Request, `payloadTooLarge` → 413, etc.) via
`SpectreErrorCategory` in `:server`.

### Schema evolution rules

Additive-safe on DTOs:

- New **optional** fields with defaults on request/response data classes
- New **sealed variants** with new `@SerialName` values (old peers answer
  `unsupportedOperation` for unknown request names)

Not additive-safe without a version bump:

- Renaming or removing fields
- Changing field types or making an optional field required
- Reusing an old `@SerialName` for a different shape (see `screenshot_v2` vs pre-#289
  `screenshot`)

## Current limitations

- **Windows needs 10 version 1803 / Server 2019 or newer.** Earlier versions
  lack native `AF_UNIX` and fail attach preflight with
  `AttachPlatformUnsupportedException`. Hosted GitHub `windows-latest` does not
  provide a reliable interactive desktop for the Robot-backed attach fixture.
  Run the full attach → exercise → detach UI test on a physical Windows desktop.
  It is opt-in. Non-UI transport and ACL tests still run on every Windows CI job:

  ```shell
  # bash / zsh / cmd
  ./gradlew :agent:test -Pspectre.agent.attachE2e.allowWindows=true --tests '*AgentAttachIntegration*'
  ```

  ```powershell
  # PowerShell: quote -P… so the shell does not split on the property name
  ./gradlew :agent:test "-Pspectre.agent.attachE2e.allowWindows=true" --tests '*AgentAttachIntegration*'
  ```
- **Wait ops.** `waitForNode`, `waitUntilGone`, `waitForVisualIdle`, and fingerprint
  `waitForIdle` are supported over agent IPC with shared deadline budgets and cancel.
  Idling-resource registration stays in-process only.
- **IntelliJ-hosted Compose**: the classloader-disambiguation rule (D-14 in the plan) was
  designed to handle `PluginClassLoader` chains. Automatic tests do not cover it yet. If
  attaching to an IntelliJ-hosted target fails, file a Spectre issue with the
  `agent-attach` label.
- **Runtime jar is separate from the API jar.** The normal `spectre-agent` dependency is not the
  jar loaded into the target JVM. Add `spectre-agent-runtime`, pass
  `AttachOptions.agentJarPath`, or set `-Ddev.sebastiano.spectre.agent.runtimeJar=...`.

## Manual verification recipe

```bash
# Terminal A — start a Compose app that depends on spectre-core (preferred path)
./gradlew :sample-desktop:run

# Find its PID (cross-platform: jps ships with the JDK)
jps -l | grep "dev.sebastiano.spectre.sample.MainKt"
# POSIX alternative: ps -A | grep "…MainKt" | awk '{print $1}'

# Terminal B — attach the agent. The agent's stderr lands in Terminal A.
./gradlew :agent:attachSpike -Ppid=<pid>
```

`attachSpike` runs separately from `:check` for manual verification.
It is not compatible with Gradle's configuration cache.

## CLI and MCP

The `spectre` executable starts a per-user local daemon when needed.
`spectre mcp` and ordinary CLI commands share its attached sessions.
MCP uses standard input and output for protocol frames.
Do not wrap it in a command that prints banners to standard output.

Start with the same target prerequisites described above, then find and attach it from a shell:

```bash
spectre ps --json
spectre attach <pid> --json
```

The attach response contains an `id`. Pass it to commands such as `tree`, `find`, `click`, and
`screenshot`. On the attach path, `screenshot` **requires** `--fullscreen` for screen-pixel
capture; default / `--window` / `--surface` fail closed (occlusion/privacy risk). Without
`--output`, a successful capture creates a temporary file and prints its path. Prefer a target
with preinstalled `spectre-core`; experimental inject also works for Compose-only hosts. See
[Requirements](#requirements). Full command and MCP reference: [CLI](cli.md).

### Claude Code recipe

Install the `spectre` executable where Claude Code can invoke it, then add it to the project's
`.mcp.json`. Use an absolute path so Claude Code does not depend on the interactive shell's
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
3. `tree`, `find`, or `find_text` / `wait_for_node` for keys, then input tools
   (`click`, `double_click`, `long_click`, `swipe`, `scroll_wheel`, `press_key`, `type_text`),
   and `wait_until_gone` after a dismissal before touching what it revealed.
4. `screenshot` with `fullscreen=true` for an inline full-desktop PNG (window/surface targets
   fail closed on attach), or `capture` / `record_*` for daemon-filesystem artefacts (paths only).
5. When the target runs under Compose Hot Reload: call `wait_for_reload_settled` **before**
   triggering a code reload (it must observe the settle chain), then re-run `tree` / `find`
   before further input.
6. **`detach`** with the retained `sessionId` when finished — releases **that** session only and
   returns leftover capture cleanup summary (count/bytes/paths + prune command when any exist).
   Unknown or already-detached sessions fail closed (`isError`). Sibling sessions stay attached;
   `detach` is never “kill the daemon” or “release all.” Detach does **not** delete capture files;
   run the summary’s `pruneCommand` for cleanup. Prefer `detach` over
   `spectre daemon kill` (which drops **every** session). The shared daemon stays up after a
   successful detach, allowing another `list_processes` / `attach` call.

### Agent session lifecycle (multi-session + disconnect)

- **Preferred cleanup order:** stop any active recording (`record_stop`) → finish or abandon
  long `wait_for_*` calls → **`detach`** that `sessionId`. Concurrent ops while detach runs
  **fail closed** (actionable errors; no hang); do not rely on them succeeding mid-teardown.
- **Two sessions:** attach A and B independently; detaching A leaves B usable. Post-detach
  `tree` / `click` / `screenshot` on A fail with a session-not-found error.
- **Client disconnect ≠ detach.** Killing or restarting the MCP front-end (stdio death, agent
  process exit) cancels in-flight **front-end** work only. The daemon keeps the target session
  until an explicit `detach`, CLI `spectre detach <session-id>`, or `spectre daemon kill`. After
  reconnect, list sessions / reuse the same `sessionId`, or detach deliberately. Disconnect
  does not create undocumented zombies and does not silently detach.
- **Recovery after front-end death:** `spectre` CLI against the same user daemon
  (`ps` / attach if needed / `tree` / `detach`), or a new MCP client talking to the same daemon.

Full MCP tool names, input/output schemas, filesystem implications, and capture-mode
distinctions: [CLI — MCP](cli.md#mcp). MCP detach success JSON is the daemon `Detached` shape
(`sessionId`; CLI `--json` uses `id`); see
[Detach success body (MCP vs CLI)](cli.md#detach-success-body-mcp-vs-cli).

Node keys can become invalid after a UI change. Run `tree` or `find` again after
an interaction. Reload-aware sessions also invalidate keys after a successful settle.
See [Compose Hot Reload awareness](hot-reload.md).

When both MCP servers are available, use each for its supported operations:

> Use Compose Hot Reload MCP to check recomposition during a live Hot Reload session.
> Use Spectre for other inspection and automation tasks.

Call MCP **`detach`** or `spectre detach <session-id>` to release one session.
`spectre daemon kill` stops the shared daemon and discards all sessions.

# Agent attach (experimental)

Spectre's `:agent` module lets you attach to a **running Compose Desktop JVM** and drive
its UI from a separate process — no HTTP listener, no network port, no need to mount routes
at target startup.

**Target shape (two paths, one API):**

1. **Preferred — preinstalled `spectre-core`** on the target (instrumented attach). Bootstrap
   finds `ComposeAutomator` on the app classpath. Use this whenever you control the target
   build.
2. **Inject — no preinstalled core** (experimental inspect). The loadable
   `spectre-agent-runtime` jar carries a nested `META-INF/spectre/inject-runtime.jar`;
   bootstrap loads Spectre core from that payload when the target only has Compose/Skiko.
   Same `AgentAttach.attach(pid)` call — no separate “inject flag”. Fine for attach → dump →
   detach (e.g. stock IntelliJ); prefer preinstalled core for sustained or high-frequency use.
   Details: [Injection without preinstalled core](#injection-without-preinstalled-core).

This is the right transport when:

- Your test JVM and the UI JVM are different processes by design, but you don't want to
  modify the UI app's startup wiring.
- You want to inspect a long-running Compose Desktop app interactively through the
  `spectre` CLI or an MCP client.
- You're attaching to an IntelliJ-hosted Compose surface from a sister process (often via
  inject when the IDE build does not ship Spectre). See [IntelliJ-hosted Compose](intellij.md)
  for VM options and the stock-IDE recipe.

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
- The target JVM needs a **Compose Desktop host** (so Spectre can find semantics owners).
  Prefer a **preinstalled** `spectre-core` dependency on the target for production-style
  attach. If core is absent, the agent runtime can **inject** a nested
  `META-INF/spectre/inject-runtime.jar` payload (experimental inspect path — see
  [Injection without preinstalled core](#injection-without-preinstalled-core)).
- **Linux, macOS, and Windows.** The transport uses native Unix Domain Sockets (`AF_UNIX`) on all
  three — no named pipes, no extra dependencies. Windows requires **Windows 10 version 1803 /
  Windows Server 2019 or newer**, when native `AF_UNIX` landed; older Windows fails the attach
  preflight with a clear message.
- The target JVM should be started with **`-XX:+EnableDynamicAgentLoading`**. Without it,
  attach prints a stderr warning per
  [JEP 451](https://openjdk.org/jeps/451) and a future JDK will reject the attach
  entirely. Spectre's launch harness adds the flag for processes it starts; stock apps and
  IDEs need it in their own VM options (see [IntelliJ-hosted Compose](intellij.md#external-attach-without-spectre-core)).

## Artifact roles

Agent attach involves two JVMs:

- **Target JVM** — the Compose app you want to inspect or drive.
- **Attacher JVM** — the test, inspector, or tool process that calls `AgentAttach.attach(pid)`.

**Preferred target shape** — preinstall Spectre core so bootstrap uses the instrumented path
(no inject classloader):

```kotlin
// build.gradle.kts of the target application
dependencies {
    implementation("dev.sebastiano.spectre:spectre-core:<version>")
    // No `spectre-agent` or `spectre-agent-runtime` dependency is needed in the target.
    // The attacher supplies the runtime jar to the JDK Attach API.
}
```

If the target cannot take that dependency (stock IDE, third-party binary), attach still works
when Compose is present and the runtime jar carries the nested inject payload — see below.

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
4. In-repo fallback at `<spectre-checkout>/agent-runtime/build/libs/agent-runtime-*.jar`,
   **only when the attacher cwd is inside a Spectre source checkout** (detected via monorepo
   markers). Published consumers should use options 1–3; the fallback is a Spectre-dev convenience.

In normal Gradle usage, `runtimeOnly(...)` makes Gradle launch the attacher with the runtime jar
listed in `java.class.path`; Spectre scans that classpath, takes the physical jar path, and passes
that path to `VirtualMachine.loadAgent(...)`. The attacher does not call classes from the runtime
jar directly, and the target still does not need `spectre-agent-runtime` declared as a dependency.

One further attacher-side switch exists, and it is not one to set casually:
`-Ddev.sebastiano.spectre.agent.inputCoordination=disabled` (or
`AttachOptions.inputCoordination = AttachInputCoordination.Disabled`) drops the attach path from
`InputLeasePolicy.Required` to `Off` in the target. It exists so a broken input coordinator is
recoverable rather than terminal, and it costs you the guarantee that no other Spectre process is
driving the same desktop. Read
[When the coordinator cannot be reached](input-coordination.md#coordinator-unreachable) first.

Classpath and directory discovery require exactly one runtime-jar candidate. If more than one
`spectre-agent-runtime-*.jar` / `agent-runtime-*.jar` is present, attach fails with
`AmbiguousAgentRuntimeJarException` naming every candidate rather than picking by classpath
order. Use `AttachOptions.agentJarPath` or `-Ddev.sebastiano.spectre.agent.runtimeJar` to choose
explicitly.

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

After that, calls such as `windows()`, `findByTestTag(...)`, `click(...)`, and `screenshot()` are
small CBOR requests over the socket. They execute inside the target JVM against the in-process
automator, then return DTOs or bytes to the attacher.

The attacher also makes a best-effort attempt to start the experimental desktop input coordinator.
Failure to start it does not block read-only attach operations. A target using the current core
selects `InputLeasePolicy.Required`, so real input then fails closed if coordination remains
unavailable. When a target has an older preinstalled core without `InputLeasePolicy`, bootstrap
falls back to its legacy no-argument `RobotDriver`; that compatibility path is uncoordinated.

### Injection without preinstalled core

Bootstrap order inside the target:

1. Prefer a **preinstalled** `ComposeAutomator` already on the target classpath
   (instrumented attach).
2. Else extract the nested **`META-INF/spectre/inject-runtime.jar`** from the agent runtime
   jar, open a child classloader parented at a Compose host loader, and load Spectre core
   from that payload (inject attach). Compose / Skiko stay on the target; only Spectre core
   and relocated kotlinx bits come from the inject jar.

Inject is an **experimental inspect** path: fine for rare attach → dump → detach sessions,
not for high-frequency CI attach loops. Prefer preinstalled core when you control the target
build. Class unload after detach is GC-dependent; do not treat inject as a leak-free
production mode.

The same `AgentAttach.attach(pid)` API is used for both paths — there is no separate
“inject flag” on the public attach surface.

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

When the attacher process is running from inside a Spectre source checkout (or a subdirectory of
one), `AgentAttach` also falls back to
`<checkout>/agent-runtime/build/libs/agent-runtime-*.jar` so local manual recipes keep working after
`./gradlew :agent-runtime:jar`. That path is **not** enabled for arbitrary application working
directories — published consumers should put `spectre-agent-runtime` on the attacher classpath or
pass an explicit path/system property.

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
    // Window/surface attach screenshots fail closed (#359); fullscreen is the only
    // screen-pixel capture mode on this path.
    val pngBytes = automator.screenshot(fullscreen = true)
} // detach + cleanup on close()
```

`AttachedAutomator` is `AutoCloseable`. Closing it sends an `AgentRequest.Detach` over the
wire; the agent stops accepting new requests, closes the target-side input coordinator session,
releases its `ComposeAutomator`, unlinks the UDS path, and removes its shutdown hook. A target-side
shutdown hook covers crash cleanup.

### `AttachOptions`

```kotlin
AttachOptions(
    agentJarPath = null,        // null = auto-locate (see "Artifact roles" above)
    udsPath = null,             // null = <base>/sp-a-<pid>-<8char-uuid>/agent.sock (see below)
    attachTimeoutMs = 5_000,    // how long to wait for the agent's IPC server to come up
)
```

The default `<base>` is `/tmp` on Linux and macOS. On Windows it is `%TEMP%`, falling back to
`%LOCALAPPDATA%\Temp` when `%TEMP%` is deep enough to push the socket path past the platform's
`sockaddr_un.sun_path` limit (102 usable bytes on macOS, 106 on Linux and Windows). A path you
pass yourself must also fit that limit; `attach` rejects longer ones with
`UdsPathTooLongException` before it loads the agent, so the failure names the path and the limit
instead of surfacing as a bootstrap error inside the target.

If you override `udsPath` with a path under an existing directory, you own that parent
directory's permissions. Spectre creates the default per-attach directory and socket owner-only —
mode 0700/0600 on POSIX, an owner-only ACL (owner full control, inherited ACEs dropped) on
Windows — but it does not tighten directories it did not create.

`AgentAttach.attach` runs a **same-user preflight** and throws
`AttachPermissionDeniedException` if the target JVM is owned by a different OS user (the
JDK Attach API only works across attach-compatible same-user processes on POSIX). On
Linux/macOS the preflight prefers numeric UID equality when both sides can be resolved, and
falls back to `ProcessHandle` usernames when UID lookup is unavailable (#166).

The JEP 451 `-XX:+EnableDynamicAgentLoading` flag is **not** verified by Spectre yet — the
JVM itself prints a stderr warning if it's missing, which is the source of truth. A follow-up
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

`windowIdentities` returns native handle/id (when resolvable), window and Compose-surface
bounds in **AWT user-space screen coordinates** (same space as `windows()` /
`locationOnScreen` / Robot), surface bounds **relative to the window** (crop rect),
per-window affine transform (`scaleX`/`scaleY`/`translateX`/`translateY`), and a
`cropRequired` flag when the surface is a subset of the top-level window (title bar or
embedded panel). For device pixels: point `(x, y) → (x * scaleX + translateX, y * scaleY +
translateY)`; scale widths/heights by `scaleX`/`scaleY` only (no translation). Daemon-owned
recording (#183) uses this so capture stays on the daemon host rather than over the
transport.

`waitForNode`, `waitForVisualIdle` (#201), `waitForIdle` (#362), and `waitUntilGone` (#438)
are available over the agent transport. `waitForIdle` runs a **fingerprint-only** wait on the
agent's in-target automator (timeout / quiet / poll; absolute deadline on the wire like
`waitForNode`). It does **not** observe idling resources registered on a different automator
instance in the app. **Idling-resource registration** and `withTracing` remain in-process-only.

`waitUntilGone` is the absence counterpart to `waitForNode` and carries the same absolute
deadline. Its timeout is category `timeout` like the other waits, but the message is the
deliverable: the in-target automator's own diagnostics — the selector, the timeout, and how many
matching nodes were still present in tracked windows — travel back verbatim, so an attach client
learns *what* is still on screen rather than only that the wait expired.

Richer input verbs (`doubleClick` / `longClick` / `swipe` / `scrollWheel` / `pressKey`)
are available over agent, HTTP, and daemon/CLI/MCP (#203). `focusWindow(nodeKey)` raises
and focuses the AWT window hosting a node over attach (#364) — use it before `pressKey` /
`typeText` when the attach client is the foreground process and the target app is not.

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

While the agent API is experimental, compatibility is **exact-match**. A version
mismatch fails attach with a clear `IOException` / `SpectreAgentException` rather than
proceeding and hanging on later frames. From 1.0 the rule may become additive-compatible
(min/max range); that change will bump `ProtocolVersion.CURRENT` and this section.

Revisions so far: **v1** bare request/response frames after `hello`; **v2** (#200) operation
envelopes with op ids, cancel, and deadline budgets; **v3** bulk payloads (`pngBytes`,
`captureJsonUtf8`) as CBOR byte strings rather than integer arrays. v3 is the reason the
handshake matters for more than op availability — it is a *representation* change that the
type system cannot see, so peers must be refused at `hello` rather than at the frame that
carries a screenshot.

### Unknown operations

A newer attacher that sends an unknown request discriminator (sealed `@SerialName` the
runtime does not know) receives `error` with category **`unsupportedOperation`**, not a
decode hang or silent close. That is how mixed-version pairs degrade.

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

They are deliberately different. The read ceiling exists because `readFrame` allocates the payload
buffer from a length it has not yet validated, so it bounds what a corrupt or desynchronised header
can make a reader allocate. Keeping it fixed and well above the write budget means two endpoints on
different budgets can never strand a response: a reader always accepts what any legitimately
configured writer sends.

The write budget is sized for the bulkiest payload, screenshots, in their worst case. PNG can only
approach raw bytes-per-pixel on incompressible content, so a 3840x2160 desktop tops out near 25 MB
of 24-bit sRGB; 64 MiB clears that with room for a dual-4K desktop.

That arithmetic only holds because bulk fields (`pngBytes`, `captureJsonUtf8`) are annotated
`@ByteString` and therefore encode as CBOR byte strings. Without it kotlinx serializes each signed
byte as its own integer, inflating the framed response to roughly **1.8x** the payload — which
would silently invalidate every budget on this page, since they are all expressed against payload
size. `WirePayloadEncodingTest` pins the encoding.

#### Raising the budget

Larger multi-monitor HiDPI rigs (dual-5K and up) can exceed the default. Raise it with either:

```shell
export SPECTRE_MAX_FRAME_BYTES=256MiB   # bytes, or a binary suffix: 512, 128k, 64M, 64MiB, 1G
spectre --max-frame-bytes 256MiB capture <session-id>
```

The flag overrides the environment variable. Both accept up to the 512 MiB read ceiling.

Propagation matters, because three processes are involved:

- The **CLI** applies the value to itself.
- A **daemon this invocation starts** inherits it on its command line. A daemon that is *already
  running* keeps the budget it booted with, and reports it in the handshake — so asking for a
  different one **fails loudly** rather than half-applying:

  ```text
  Cannot honour --max-frame-bytes=256MiB (268435456 bytes): the running Spectre daemon started
  with 64MiB (67108864 bytes), and a daemon keeps the budget it booted with. Run
  `spectre daemon kill` and retry so the new budget applies to the daemon and to the JVMs it
  injects.
  ```

  Only an explicit request is refused, and *explicit* means the option or the variable was
  present — not that its value differs from the default. Asking for `64MiB` against a `128MiB`
  daemon is a conflict like any other. Leaving the budget alone asks for nothing, so a daemon
  running something larger is accepted silently: readers take frames up to the ceiling whatever
  their own budget. A daemon too old to report its budget cannot have been started with the
  requested one, so it is refused the same way rather than assumed compatible.
- The **injected agent** cannot read the daemon's environment, and it is the process that writes
  the bulky screenshot frames, so the daemon forwards its resolved budget in `agentArgs` (see
  below). It applies before the agent's IPC server accepts a request.

Because readers are permissive up to the ceiling, no hop ever rejects a frame a peer legitimately
sent; the refusal above is about the *request* not being honourable, not about the wire.

#### Over-budget behaviour

Exceeding the write budget is **fail-closed**:

- Responses that encode larger than the budget are **not** truncated or spilled to disk by the
  agent transport. The runtime replies with `error` category **`payloadTooLarge`** and a message
  that includes the sizes.
- The connection stays open; subsequent ops on the same session continue normally.
- Parity CI can rely on deterministic taxonomy behaviour instead of size-threshold flakes.

The one exception is the **fullscreen still**, whose size nothing bounds: rather than failing, it
drops to logical resolution first, and only reports `payloadTooLarge` if even that overruns. See
[Atomic capture — Pixel scale](capture.md#pixel-scale).

Spill-to-file for large captures remains a higher-level concern (capture directories / daemon
shared FS from #181); the wire layer does not invent a second path for oversized frames.

#### `agentArgs` format

`-javaagent:spectre-agent-runtime.jar=<agentArgs>` accepts two forms:

| Form | Example | Used by |
|---|---|---|
| Bare UDS path | `/tmp/sp-a-123-abcd/agent.sock` | hand-written `-javaagent:` lines; still **accepted** |
| Structured | `uds=/tmp/sp-a-123-abcd/agent.sock,maxFrameBytes=268435456` | everything Spectre **emits** |

The structured form is recognised by a `uds=` prefix *and* at least one further `,`-separated
field. Values Spectre emits are percent-escaped, so a UDS path containing `,` or `=` survives the
round trip instead of truncating. The one shape that stays ambiguous is a **hand-written** bare
path that both starts with `uds=` and contains a comma — `uds=agent,1.sock` is read as structured.
Any prefix-based discriminator has such a case; this one is documented rather than chased, since
Spectre only ever emits the structured form and the bare form exists for `-javaagent:` lines you
write yourself.

Unknown keys and unparseable values are ignored rather than failing the attach, so a runtime that
understands this format but not a newer key still gets a working session. That tolerance does not
extend to a runtime predating the format: it reads the whole string as a socket path, binds
somewhere else, and the attach times out. Spectre resolves the runtime JAR from the attacher, so
that pairing is only reachable by overriding it (`AttachOptions.agentJarPath` or the runtime-jar
system property), the bootstrap timeout names the possibility, and `ProtocolVersion.CURRENT` is
bumped whenever the payload representation changes so a mismatched pair fails the handshake rather
than a later frame.

Spectre always emits the budget, **including the default one**. The target may have been launched
with a `SPECTRE_MAX_FRAME_BYTES` of its own, and letting that win would leave the daemon and the
JVM it injected disagreeing about the hop between them — a 16MiB target under a 64MiB daemon would
fail captures the daemon could carry.

HTTP maps `payloadTooLarge` → **413 Payload Too Large**.

### Long operations and cancel (#200)

After Hello, every request is an **operation envelope** (`opId`, optional absolute
`deadlineEpochMs`, body). Responses are correlated by `opId` so multiple ops can share one
connection.

- Long work (future waits, heavy capture) runs on a **worker thread**, not the accept loop —
  so cancel/detach stay responsive.
- **Cancel** is an explicit wire op (`cancel` with the target `opId`), not socket-close.
- Closing a CLI/MCP front-end during a long-poll cancels only that **front-end** connection's
  in-flight work. The **daemon session stays attached** until an explicit `detach` (or daemon
  kill / crash). Reconnect or use the CLI against the same session id — disconnect is not
  detach and does not leave an undocumented zombie without a recovery path (see lifecycle
  below). The agent transport itself only detaches on an explicit `detach` or handshake
  failure teardown.


Clients should branch on `category` (see `AgentErrorCategory` / `SpectreAgentException`),
not on free-text `message`. The HTTP transport maps the same names onto status codes
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

- **Windows needs 10 version 1803 / Server 2019 or newer.** That's when native `AF_UNIX` landed;
  older Windows fails the attach preflight with `AttachPlatformUnsupportedException`. Hosted
  GitHub `windows-latest` is not a reliable interactive desktop for the Robot-backed attach
  fixture; the full attach → exercise → detach UI e2e is opt-in on physical Windows desktops
  (non-UI transport/ACL tests still run on every Windows CI job):

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
  designed to handle `PluginClassLoader` chains but isn't automatically tested yet. If
  you hit issues attaching to an IntelliJ-hosted target, file a Spectre issue with the
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
`screenshot`. On the attach path, `screenshot` **requires** `--fullscreen` for screen-pixel
capture; default / `--window` / `--surface` fail closed (occlusion/privacy risk). Without
`--output`, a successful capture creates a temporary file and prints its path. Prefer a target
with preinstalled `spectre-core`; experimental inject also works for Compose-only hosts — see
[Requirements](#requirements). Full command and MCP reference: [CLI](cli.md).

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
3. `tree`, `find`, or `find_text` / `wait_for_node` for keys, then input tools
   (`click`, `double_click`, `long_click`, `swipe`, `scroll_wheel`, `press_key`, `type_text`),
   and `wait_until_gone` after a dismissal before touching what it revealed.
4. `screenshot` with `fullscreen=true` for an inline full-desktop PNG (window/surface targets
   fail closed on attach), or `capture` / `record_*` for daemon-filesystem artifacts (paths only).
5. When the target runs under Compose Hot Reload: call `wait_for_reload_settled` **before**
   triggering a code reload (it must observe the settle chain), then re-run `tree` / `find`
   before further input.
6. **`detach`** with the retained `sessionId` when finished — releases **that** session only and
   returns leftover capture cleanup summary (count/bytes/paths + prune command when any exist).
   Unknown or already-detached sessions fail closed (`isError`). Sibling sessions stay attached;
   `detach` is never “kill the daemon” or “release all.” Detach does **not** delete capture files;
   run the summary’s `pruneCommand` when you want cleanup. Prefer `detach` over
   `spectre daemon kill` (which drops **every** session). The shared daemon stays up after a
   successful detach so you can `list_processes` / `attach` again.

### Agent session lifecycle (multi-session + disconnect)

- **Preferred cleanup order:** stop any active recording (`record_stop`) → finish or abandon
  long `wait_for_*` calls → **`detach`** that `sessionId`. Concurrent ops while detach runs
  **fail closed** (actionable errors; no hang); do not rely on them succeeding mid-teardown.
- **Two sessions:** attach A and B independently; detaching A leaves B usable. Post-detach
  `tree` / `click` / `screenshot` on A fail closed with session-not-found honesty.
- **Client disconnect ≠ detach.** Killing or restarting the MCP front-end (stdio death, agent
  process exit) cancels in-flight **front-end** work only. The daemon keeps the target session
  until an explicit `detach`, CLI `spectre detach <session-id>`, or `spectre daemon kill`. After
  reconnect, list sessions / reuse the same `sessionId`, or detach deliberately — disconnect
  does not create undocumented zombies and does not silently detach.
- **Recovery after front-end death:** `spectre` CLI against the same user daemon
  (`ps` / attach if needed / `tree` / `detach`), or a new MCP client talking to the same daemon.

Full MCP tool names, input/output schemas, filesystem implications, and capture-mode
distinctions: [CLI — MCP](cli.md#mcp). MCP detach success JSON is the daemon `Detached` shape
(`sessionId`, not CLI `--json` `id`) — see
[Detach success body (MCP vs CLI)](cli.md#detach-success-body-mcp-vs-cli).

Node keys are short-lived: get a fresh key with `tree` or `find` after an interaction changes the
UI. On reload-aware sessions, keys are also invalidated after a successful hot reload settle —
see [Compose Hot Reload awareness](hot-reload.md).

If the agent also has Compose Hot Reload’s MCP configured, do not alternate randomly:

> If you have HR available and want quick sanity checks while iterating on a live app, use the
> HR MCP; in any other case, Spectre is the right choice.

Call the MCP **`detach`** tool (or `spectre detach <session-id>` from a shell) to release one
session, or `spectre daemon kill` to stop the shared daemon and discard all sessions when you are
finished.

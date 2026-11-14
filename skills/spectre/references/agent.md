# Agent attach

Use this reference for programmatic attach to a running Compose JVM via `AgentAttach`, `AttachedAutomator`,
`AttachOptions`, and the `spectre-agent`/`spectre-agent-runtime` artefacts. Published guides:
<https://spectre.sebastiano.dev/guide/agent/> and <https://spectre.sebastiano.dev/guide/installation/> (`docs/guide/` in
the Spectre repository).

## Two-JVM architecture and target bootstrap paths

`AgentAttach.attach(pid)` connects an **attacher JVM** (test, inspector, or CLI) to a **target JVM** hosting Compose:

1. **Preferred — preinstalled `spectre-core`** (instrumented attach): bootstrap finds `ComposeAutomator` on the target's
   classpath. Prefer this whenever you control the target build.
2. **Inject — no preinstalled core** (experimental inspection): `spectre-agent-runtime` embeds
   `META-INF/spectre/inject-runtime.jar`. When `ComposeAutomator` is absent from the target classpath, bootstrap
   extracts `inject-runtime.jar` into a child classloader parented at the Compose/Skiko host loader. The same
   `AgentAttach.attach(pid)` call handles both paths with zero Spectre dependencies in a stock target binary. Because
   class unloading after detach relies on GC, prefer preinstalled `spectre-core` for sustained or repeated test runs.

## Gradle dependencies

Controlled target app (preferred; omit for stock third-party binaries using inject):

```kotlin
dependencies {
    implementation("dev.sebastiano.spectre:spectre-core:<version>")
}
```

Attacher/inspector JVM (`testImplementation`/`testRuntimeOnly` in test source sets):

```kotlin
dependencies {
    implementation("dev.sebastiano.spectre:spectre-agent:<version>")
    runtimeOnly("dev.sebastiano.spectre:spectre-agent-runtime:<version>")
}
```

Do **not** add `spectre-agent` or `spectre-agent-runtime` to the target app unless it also attaches to itself, and do
**not** invent an `-all` classifier: `spectre-agent-runtime` is a distinct artefact ID for the loadable Java agent jar.

## Attach sequence and runtime jar discovery

`AgentAttach.attach(pid)`:

1. Resolves `spectre-agent-runtime-*.jar` from `AttachOptions.agentJarPath`, `-Ddev.sebastiano.spectre.agent.runtimeJar`,
   or the attacher's `java.class.path`.
2. Allocates an owner-only Unix Domain Socket (`/tmp/sp-a-<pid>-<8char-uuid>/agent.sock` on Linux/macOS, or under
   `%TEMP%`/`java.io.tmpdir` on Windows 10 1803+ / Server 2019+ via native `AF_UNIX`, never named pipes).
3. Verifies platform support and same-OS-user ownership, calls `VirtualMachine.attach(pid).loadAgent(jar, udsPath)`,
   starts the UDS server inside `SpectreAgent.agentmain(...)` (preferring preinstalled `ComposeAutomator` or falling
   back to `META-INF/spectre/inject-runtime.jar`), and returns an `AttachedAutomator` IPC client.

When a shaded launcher, module path, or wrapper script hides `spectre-agent-runtime-*.jar` from `java.class.path`, pass
it explicitly via `AttachOptions` or system property on the attacher JVM:

```kotlin
AgentAttach.attach(
    pid = targetPid,
    options = AttachOptions(agentJarPath = Path.of("/abs/path/to/spectre-agent-runtime-<version>.jar")),
)
// Or on the attacher JVM: -Ddev.sebastiano.spectre.agent.runtimeJar=/abs/path/to/spectre-agent-runtime-<version>.jar
```

## Operational caveats

- **Target JVM flags and user:** attacher and target must run as the same OS user; pass `-XX:+EnableDynamicAgentLoading`
  on JDK 21+ targets to avoid dynamic-agent warnings or future restrictions.
- **Trust boundary:** UDS IPC is local and unauthenticated, gated by filesystem permissions (POSIX `0700`/`0600` or
  Windows owner-only ACL) for trusted dev/test hosts.
- **Focus for real OS input:** synthetic input (the attach default) needs no OS focus; when real OS input is active and
  the target window may be unfocused, call `AttachedAutomator.focusWindow(nodeKey)` before `typeText` or `pressKey`.
- **Attach-path limits:** `waitForIdle` is semantics-fingerprint-only (live `AutomatorIdlingResource` registration stays
  in-process). Direct `screenshot` over attach requires fullscreen; for single-window pixels plus semantics, use
  `spectre capture` (or `AttachedAutomator.capture` when the target JVM includes `spectre-recording` and its OS helper).
- **JUnit wrapper:** `LaunchAndAttachExtension` and `LaunchAndAttachRule` wrap this attach lifecycle for per-test target
  JVMs (`references/junit.md`).

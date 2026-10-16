# Agent Attach

Use this reference for `AgentAttach`, `AttachedAutomator`, `AttachOptions`, and agent
dependencies. It covers attach to a running Compose JVM.

## Source of truth

- Published user guide: <https://spectre.sebastiano.dev/guide/agent/>
- Published installation guide: <https://spectre.sebastiano.dev/guide/installation/>
- Repo docs, when working inside Spectre: `docs/guide/agent.md` and
  `docs/guide/installation.md`

Check the user guide for full setup instructions.

## Attach setup

Agent attach uses two JVMs. `AgentAttach.attach(pid)` supports two target setups:

- **Target JVM**: the Compose app being inspected or driven.
  1. **Preferred — preinstalled `spectre-core`** (instrumented attach). Bootstrap
     finds `ComposeAutomator` on the app classpath. Use this for target builds
     under test control.
  2. **Inject — no preinstalled core** (experimental inspect). The loadable
     `spectre-agent-runtime` jar carries a nested `META-INF/spectre/inject-runtime.jar`.
     Bootstrap loads Spectre core from that payload when the target only has
     Compose/Skiko. The same `AgentAttach.attach(pid)` call needs no separate inject flag.
     Use for occasional attach → dump → detach (e.g. stock IntelliJ); prefer preinstalled
     core for sustained or high-frequency use.
- **Attacher JVM**: the test, inspector, or tool process that calls
  `AgentAttach.attach(pid)`. It compiles against `spectre-agent` and normally has
  `spectre-agent-runtime` on its runtime classpath.

The attacher supplies `spectre-agent-runtime` through the JDK Attach API.
The target app does not need `spectre-agent` or `spectre-agent-runtime` as dependencies
unless it also acts as the attacher.

Current bootstrap injects core when it is absent from the target JVM.
Preinstalled `spectre-core` remains the preferred setup when the target build is controlled.

## Dependencies

For a controlled target app build (preferred):

```kotlin
dependencies {
    implementation("dev.sebastiano.spectre:spectre-core:<version>")
}
```

For a stock IDE or third-party binary, attach can inject core if Compose is present
and the runtime jar contains the nested payload. The target needs no extra Spectre
dependency.

For the attacher/test process:

```kotlin
dependencies {
    implementation("dev.sebastiano.spectre:spectre-agent:<version>")
    runtimeOnly("dev.sebastiano.spectre:spectre-agent-runtime:<version>")
}
```

Use `testImplementation` / `testRuntimeOnly` instead when the attacher is a test
source set.

Use the separate `spectre-agent-runtime` artifact ID for the loadable agent jar.
Do not use an `-all` classifier.

## What happens during attach

`AgentAttach.attach(pid)`:

1. Resolves the loadable `spectre-agent-runtime-<version>.jar`.
2. Creates a fresh Unix Domain Socket path such as
   `/tmp/sp-a-<pid>-<8char-uuid>/agent.sock` on Linux/macOS, or under `%TEMP%` /
   `java.io.tmpdir` on Windows (`…\sp-a-<pid>-<8char-uuid>\agent.sock`).
3. Runs attach preflights such as platform support (native `AF_UNIX`, including
   Windows 10 1803+ / Server 2019+) and same-OS-user checks.
4. Calls `VirtualMachine.attach(pid).loadAgent(runtimeJarPath, udsPath)`.
5. Lets the target JVM invoke `SpectreAgent.agentmain(...)`.
6. In the target JVM, bootstrap **prefers** a preinstalled `ComposeAutomator` on
   the app classpath. If that class is absent, it extracts
   `META-INF/spectre/inject-runtime.jar`, opens a child classloader parented at a
   Compose host loader, and loads Spectre core from that payload. Then it creates
   an in-process automator and starts a UDS IPC server.
7. Connects the attach-side `IpcClient` and returns `AttachedAutomator`.

After attach, `AttachedAutomator` sends IPC requests to the target JVM.

Inject is an **experimental inspection** path. Class unloading after detach depends
on garbage collection. It has no leak-free production guarantee.

## Runtime jar discovery and custom attach

`AgentAttach` scans the attacher's `java.class.path` for a physical
`spectre-agent-runtime-*.jar`. It passes that path to `VirtualMachine.loadAgent(...)`.
The target does not declare the runtime jar. Attacher code does not call its classes.

If a custom launcher, shaded distribution, module-path launch, or script hides the
physical runtime jar from `java.class.path`, use one of these explicit forms:

```kotlin
AgentAttach.attach(
    pid = targetPid,
    options =
        AttachOptions(
            agentJarPath = Path.of("/abs/path/to/spectre-agent-runtime-<version>.jar"),
        ),
)
```

Or set this on the attacher JVM:

```text
-Ddev.sebastiano.spectre.agent.runtimeJar=/abs/path/to/spectre-agent-runtime-<version>.jar
```

## Caveats to mention

- Supported on Linux, macOS, and Windows (native `AF_UNIX`; Windows needs 10 version
  1803 / Server 2019 or newer). The transport uses no named pipes.
- The attacher and target must run as the same OS user.
- The target should start with `-XX:+EnableDynamicAgentLoading`.
- Communication is local UDS IPC, unauthenticated, and intended for trusted
  dev/test machines. Trust is filesystem ownership (POSIX 0700/0600 or Windows
  owner-only ACL).
- Window/surface attach screenshots fail closed; fullscreen is the only
  screen-pixel capture mode on this path.
- `waitForIdle` over attach is a fingerprint-only wait. Idling-resource
  registration stays in-process.
- JUnit launch-and-attach (`LaunchAndAttachExtension` / `LaunchAndAttachRule`) uses
  this same attach path; see `references/junit.md` and the user-guide JUnit page.

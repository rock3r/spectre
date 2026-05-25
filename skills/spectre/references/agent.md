# Agent Attach

Use this reference when the user asks about `AgentAttach`, `AttachedAutomator`,
`AttachOptions`, attaching to a running Compose JVM, or the `spectre-agent` /
`spectre-agent-runtime` dependency shape.

## Source of truth

- Published user guide: <https://spectre.sebastiano.dev/guide/agent/>
- Published installation guide: <https://spectre.sebastiano.dev/guide/installation/>
- Repo docs, when working inside Spectre: `docs/guide/agent.md` and
  `docs/guide/installation.md`

Prefer the user guide for detailed setup. Keep answers aligned with it.

## Mental model

Agent attach involves two JVMs:

- **Target JVM**: the Compose app being inspected or driven. It must already have
  `spectre-core` on its classpath.
- **Attacher JVM**: the test, inspector, or tool process that calls `AgentAttach.attach(pid)`.
  It compiles against `spectre-agent` and normally has `spectre-agent-runtime` on its runtime
  classpath.

Do not tell users to add `spectre-agent` or `spectre-agent-runtime` to the target app unless they
are also writing the attacher in that same process. The runtime jar is supplied by the attacher and
loaded into the target through the JDK Attach API.

## Dependencies

For the target app:

```kotlin
dependencies {
    implementation("dev.sebastiano.spectre:spectre-core:<version>")
}
```

For the attacher/test process:

```kotlin
dependencies {
    implementation("dev.sebastiano.spectre:spectre-agent:<version>")
    runtimeOnly("dev.sebastiano.spectre:spectre-agent-runtime:<version>")
}
```

Use `testImplementation` / `testRuntimeOnly` instead when the attacher is a test source set.

Do not recommend an `-all` classifier. `spectre-agent-runtime` is a separate artifactId because it
is the loadable Java-agent runtime, not the normal API jar.

## What happens during attach

`AgentAttach.attach(pid)`:

1. Resolves the loadable `spectre-agent-runtime-<version>.jar`.
2. Creates a fresh Unix Domain Socket path such as
   `/tmp/sp-a-<pid>-<8char-uuid>/agent.sock`.
3. Runs attach preflights such as platform support and same-OS-user checks.
4. Calls `VirtualMachine.attach(pid).loadAgent(runtimeJarPath, udsPath)`.
5. Lets the target JVM invoke `SpectreAgent.agentmain(...)`.
6. In the target JVM, finds `ComposeAutomator` from the target's existing `spectre-core`
   dependency, creates an in-process automator, and starts a UDS IPC server.
7. Connects the attach-side `IpcClient` and returns `AttachedAutomator`.

After that, `AttachedAutomator` methods send small IPC requests to the target JVM.

## Runtime jar discovery and custom attach

Classpath auto-discovery means `AgentAttach` scans the attacher's `java.class.path` for a physical
`spectre-agent-runtime-*.jar`, then passes that jar path to `VirtualMachine.loadAgent(...)`.
It does not mean the target app declared `spectre-agent-runtime`, and it does not mean user code
should call runtime classes directly.

If a custom launcher, shaded distribution, module-path launch, or script hides the physical runtime
jar from `java.class.path`, use one of these explicit forms:

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

- Current preview support is macOS and Linux only.
- The attacher and target must run as the same OS user.
- The target should start with `-XX:+EnableDynamicAgentLoading`.
- Communication is local UDS IPC, unauthenticated, and intended for trusted dev/test machines.

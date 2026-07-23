# #209 user-like inject attach recipe (M3)

## Automated path (CI / developer)

```bash
./gradlew :agent:test --tests '*AgentInjectAttachIntegrationTest*'
```

What it does (real shipped APIs):

1. Builds `spectre-agent-runtime` with nested `META-INF/spectre/inject-runtime.jar`.
2. Spawns `InjectComposeFixtureMain` on a child JVM **without** `spectre-core` on its classpath.
3. Calls `AgentAttach.attach(pid)` → UDS → `windows()` / `allNodes()` / `findByTestTag(...)`.
4. Asserts non-empty tree and fixture tags.

## Manual recipe (same components)

`./gradlew :agent:attachSpike` only loads the agent in diagnostic mode (window count on the
**target** stderr) — it does **not** return an `AttachedAutomator` or print a tree. Prefer the
Gradle e2e above for a full tree dump. For an interactive attach that queries the tree:

```bash
# Terminal A — target without spectre-core:
./gradlew :agent-test-fixture:jar :agent-runtime:jar
# Build a classpath of Compose + agent-test-fixture only (no spectre-core), then:
java -cp "$COMPOSE_AND_FIXTURE_CP" \
  -XX:+EnableDynamicAgentLoading \
  dev.sebastiano.spectre.agent.fixture.InjectComposeFixtureMainKt
```

```kotlin
// Terminal B — attacher JVM (spectre-agent + spectre-agent-runtime on CP):
@file:OptIn(ExperimentalSpectreAgentApi::class)

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Path

AgentAttach.attach(
    pid = targetPid, // from jps / SpectreProcesses
    options =
        AttachOptions(
            agentJarPath = Path.of("agent-runtime/build/libs/…spectre-agent-runtime….jar"),
        ),
).use { automator ->
    println(automator.windows())
    println(automator.allNodes().map { it.testTag })
    println(automator.findByTestTag("agent-fixture-label"))
}
```

Success signal: non-empty `windows()` / `allNodes()` / fixture tags. Failure signal: bootstrap
exceptions (`SpectreNotOnClasspathException`, `ComposeNotOnClasspathException`, attach timeouts)
with the same categories documented in agent guide + decision doc.

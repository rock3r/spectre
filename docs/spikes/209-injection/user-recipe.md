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

```bash
# Terminal A — target without spectre-core (filter core off the classpath yourself,
# or run a Compose app that does not depend on spectre-core):
./gradlew :agent-test-fixture:jar :agent-inject-runtime:shadowJar :agent-runtime:jar
# Construct a CP of Compose + agent-test-fixture classes only, then:
java -cp "$COMPOSE_AND_FIXTURE_CP" \
  -XX:+EnableDynamicAgentLoading \
  dev.sebastiano.spectre.agent.fixture.InjectComposeFixtureMainKt

# Terminal B — attach with the agent runtime that embeds inject-runtime:
./gradlew :agent:attachSpike -Ppid=<pid-from-jps>
# or from a test/tool using AgentAttach.attach(pid, AttachOptions(agentJarPath = ...))
```

Success signal: non-empty `windows()` / `allNodes()` / fixture tags. Failure signal: bootstrap
exceptions (`SpectreNotOnClasspathException`, `ComposeNotOnClasspathException`, attach timeouts)
with the same categories documented in agent guide + decision doc.

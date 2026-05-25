package dev.sebastiano.spectre.agent

import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Configuration for [AgentAttach.attach].
 *
 * Lookup order for the agent JAR (when [agentJarPath] isn't explicitly set):
 * 1. The system property `dev.sebastiano.spectre.agent.runtimeJar` (used by Spectre's own
 *    integration tests so they don't have to know the absolute build path).
 * 2. A `spectre-agent-runtime-<version>.jar` or `agent-runtime-<version>.jar` entry on the
 *    attacher's `java.class.path`.
 * 3. `<cwd>/agent-runtime/build/libs/agent-runtime-<version>.jar` if the worktree layout matches.
 *
 * The fallback throws when none of the candidates exist, with a message pointing the user at
 * `./gradlew :agent-runtime:jar` to produce the JAR.
 *
 * **JEP 451 flag detection** is **not** implemented yet. The previous draft tried to read a
 * `jdk.internal.vm.dynamic.agent.loading` system property that doesn't actually exist;
 * `VirtualMachine.getSystemProperties()` returns system properties, not HotSpot VM flags. Until we
 * wire up a reliable preflight (likely through `HotSpotDiagnosticMXBean` via the Attach API's local
 * management agent), we rely on the JVM's own JEP 451 stderr warning to tell users when the flag is
 * missing.
 *
 * @property agentJarPath loadable agent runtime JAR to pass to `VirtualMachine.loadAgent`.
 * @property udsPath Unix Domain Socket path the agent should bind on (must NOT exist already;
 *   defaults to `/tmp/sp-a-<pid>-<8char-uuid>/agent.sock` with a fresh UUID per `attach()` call so
 *   concurrent attaches don't collide). If you override this with a path under an existing
 *   directory, you own that parent directory's permissions; Spectre only tightens directories it
 *   creates itself.
 * @property attachTimeoutMs how long to wait for the agent's bootstrap + IPC server to come up.
 */
@ExperimentalSpectreAgentApi
public data class AttachOptions(
    public val agentJarPath: Path? = null,
    public val udsPath: Path? = null,
    public val attachTimeoutMs: Long = DEFAULT_ATTACH_TIMEOUT_MS,
) {
    public companion object {
        public const val DEFAULT_ATTACH_TIMEOUT_MS: Long = 5_000

        /**
         * Default UDS path: `/tmp/sp-a-<pid>-<8char-uuid>/agent.sock`. Deliberately short to stay
         * inside Unix's `sun_path` limit (~104 chars on macOS, ~108 on Linux). `java.io.tmpdir`
         * resolves to a much longer path on macOS (`/var/folders/...`) and can blow past the limit,
         * so we hard-code `/tmp` (symlinked to `/private/tmp` on macOS).
         */
        public fun defaultUdsPath(targetPid: Long): Path {
            val shortUuid = UUID.randomUUID().toString().take(SHORT_UUID_LENGTH)
            return Paths.get("/tmp", "sp-a-${targetPid}-${shortUuid}", "agent.sock")
        }

        private const val SHORT_UUID_LENGTH = 8
    }
}

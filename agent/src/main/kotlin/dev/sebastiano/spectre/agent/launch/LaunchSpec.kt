package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Path

/**
 * Specification for starting a process and attaching Spectre to its app JVM.
 *
 * Prefer **prod-like** launches (`java -jar …`, installDist binaries, packaged apps). Gradle `run`
 * / `hotRun` are supported but detected and warned about — see
 * [LaunchCommandRewriter.isGradleishLaunch] and [LaunchCommandRewriter.gradleLaunchWarning].
 *
 * @property command argv to start (first token is the executable).
 * @property workingDirectory optional cwd for the process; null keeps the attacher's cwd.
 * @property environment extra environment entries merged over the current process env.
 * @property extraJvmArgs JVM flags inserted after the `java` binary for direct JVM launches (after
 *   any automatic `-XX:+EnableDynamicAgentLoading` injection). Ignored for non-JVM command lines.
 * @property captureDirectory directory for stdout/stderr capture files; created if missing. When
 *   null, a fresh temp directory is allocated under `java.io.tmpdir`.
 * @property stageTimeouts per-stage readiness budgets.
 * @property attachOptions agent JAR / UDS / attach-timeout options used at the bootstrap stage.
 * @property appJvmNameFilter optional case-insensitive substring of
 *   [dev.sebastiano.spectre.agent.JvmProcessInfo.displayName] used when discovering a Gradle-
 *   spawned app JVM among descendants. When null on a Gradle-ish launch, any JVM descendant of the
 *   client process (excluding the Gradle daemon) is considered.
 * @property injectDynamicAgentLoading when true (default), direct `java` launches get
 *   `-XX:+EnableDynamicAgentLoading` injected unless the flag is already present.
 * @property requireSpectreCoreOnClasspath when true, direct JVM launches whose command line exposes
 *   a classpath are rejected early if no `spectre-core` jar entry is found.
 */
@ExperimentalSpectreAgentApi
public data class LaunchSpec(
    public val command: List<String>,
    public val workingDirectory: Path? = null,
    public val environment: Map<String, String> = emptyMap(),
    public val extraJvmArgs: List<String> = emptyList(),
    public val captureDirectory: Path? = null,
    public val stageTimeouts: LaunchStageTimeouts = LaunchStageTimeouts(),
    public val attachOptions: AttachOptions = AttachOptions(),
    public val appJvmNameFilter: String? = null,
    public val injectDynamicAgentLoading: Boolean = true,
    public val requireSpectreCoreOnClasspath: Boolean = false,
) {
    init {
        require(command.isNotEmpty()) { "LaunchSpec.command must not be empty" }
        require(command.first().isNotBlank()) {
            "LaunchSpec.command[0] (executable) must not be blank"
        }
    }
}

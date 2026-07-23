package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Path

/**
 * Base for launch-and-attach readiness failures. Each subclass binds a distinct [stage] so callers
 * can branch without parsing free-text messages.
 *
 * Lives outside the [dev.sebastiano.spectre.agent.SpectreAttachException] sealed hierarchy so
 * launch-stage taxonomy stays independent of attach-only failure types (and Kotlin sealed
 * subclasses must share a package with their parent).
 */
@ExperimentalSpectreAgentApi
public sealed class LaunchException(
    public val stage: LaunchStage,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Stage [LaunchStage.PROCESS_ALIVE]: the launched process exited before it became attachable.
 *
 * @property exitCode process exit value (may be non-zero or zero).
 * @property stderrExcerpt truncated contents of the stderr capture file at failure time.
 * @property stdoutPath absolute path of the stdout capture file for this launch.
 * @property stderrPath absolute path of the stderr capture file for this launch.
 */
@ExperimentalSpectreAgentApi
public class ProcessExitedBeforeAttachException(
    public val exitCode: Int,
    public val stderrExcerpt: String,
    public val stdoutPath: Path,
    public val stderrPath: Path,
    cause: Throwable? = null,
) :
    LaunchException(
        stage = LaunchStage.PROCESS_ALIVE,
        message =
            buildString {
                append("Launched process exited with code $exitCode before attach ")
                append("(stage=${LaunchStage.PROCESS_ALIVE}). ")
                append("stdout=$stdoutPath stderr=$stderrPath")
                if (stderrExcerpt.isNotBlank()) {
                    append("\n--- captured stderr (excerpt) ---\n")
                    append(stderrExcerpt)
                    if (!stderrExcerpt.endsWith("\n")) append('\n')
                    append("--- end stderr ---")
                }
            },
        cause = cause,
    )

/**
 * Stage [LaunchStage.JVM_ATTACHABLE]: the process stayed alive but no attachable JVM was found
 * within the stage timeout (direct PID or Gradle descendant discovery).
 */
@ExperimentalSpectreAgentApi
public class JvmNotAttachableException(
    public val launchedPid: Long,
    public val timeoutMs: Long,
    public val stdoutPath: Path,
    public val stderrPath: Path,
    public val detail: String = "",
) :
    LaunchException(
        stage = LaunchStage.JVM_ATTACHABLE,
        message =
            buildString {
                append("No attachable JVM for launched pid=$launchedPid within ${timeoutMs}ms ")
                append("(stage=${LaunchStage.JVM_ATTACHABLE}). ")
                append("stdout=$stdoutPath stderr=$stderrPath")
                if (detail.isNotBlank()) append(" $detail")
            },
    )

/**
 * Stage [LaunchStage.AGENT_BOOTSTRAP]: the target JVM was attachable but agent load / bootstrap /
 * UDS connect failed.
 */
@ExperimentalSpectreAgentApi
public class LaunchAgentBootstrapException(
    public val attachedPid: Long,
    public val stdoutPath: Path,
    public val stderrPath: Path,
    cause: Throwable? = null,
) :
    LaunchException(
        stage = LaunchStage.AGENT_BOOTSTRAP,
        message =
            "Agent bootstrap failed for pid=$attachedPid " +
                "(stage=${LaunchStage.AGENT_BOOTSTRAP}). " +
                "stdout=$stdoutPath stderr=$stderrPath" +
                (cause?.message?.let { ": $it" }.orEmpty()),
        cause = cause,
    )

/**
 * Stage [LaunchStage.FIRST_WINDOW]: attach succeeded but no windows appeared within the stage
 * timeout.
 */
@ExperimentalSpectreAgentApi
public class FirstWindowTimeoutException(
    public val attachedPid: Long,
    public val timeoutMs: Long,
    public val stdoutPath: Path,
    public val stderrPath: Path,
    cause: Throwable? = null,
) :
    LaunchException(
        stage = LaunchStage.FIRST_WINDOW,
        message =
            "Attached pid=$attachedPid but no window appeared within ${timeoutMs}ms " +
                "(stage=${LaunchStage.FIRST_WINDOW}). " +
                "stdout=$stdoutPath stderr=$stderrPath",
        cause = cause,
    )

/**
 * Pre-flight failure: a direct JVM launch's classpath does not include `spectre-core` and
 * [LaunchSpec.requireSpectreCoreOnClasspath] is true.
 */
@ExperimentalSpectreAgentApi
public class SpectreCoreMissingOnClasspathException(public val classpath: String) :
    LaunchException(
        stage = LaunchStage.PROCESS_ALIVE,
        message =
            "LaunchSpec.requireSpectreCoreOnClasspath is true but the target classpath does " +
                "not contain a spectre-core jar entry. Classpath excerpt: " +
                classpath.take(CLASSPATH_EXCERPT_CHARS),
    )

private const val CLASSPATH_EXCERPT_CHARS: Int = 512

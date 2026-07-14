package dev.sebastiano.spectre.agent

/** Base for all attach-side failures surfaced by [AgentAttach.attach]. */
@ExperimentalSpectreAgentApi
public sealed class SpectreAttachException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Thrown when the JDK Attach API isn't available — the attacher is running on a JRE rather than a
 * JDK, or `jdk.attach` is not in the module graph (rare, but possible with custom JLink images).
 */
@ExperimentalSpectreAgentApi
public class AttachUnsupportedException(cause: Throwable? = null) :
    SpectreAttachException(
        "The JDK Attach API is not available on this JVM. Spectre's agent attach requires a JDK " +
            "(not a JRE) with the `jdk.attach` module on the module path. The class " +
            "`com.sun.tools.attach.VirtualMachine` could not be loaded.",
        cause,
    )

/** Thrown when the attaching JVM is older than Spectre's minimum supported Java version. */
@ExperimentalSpectreAgentApi
public class JavaVersionUnsupportedException(javaFeature: Int) :
    SpectreAttachException(
        "Spectre's agent attach requires JDK 21 or newer, but this JVM reports Java $javaFeature. " +
            "Run the CLI with a JDK 21+ distribution."
    )

/** Thrown when the current operating system does not support the agent transport. */
@ExperimentalSpectreAgentApi
public class AttachPlatformUnsupportedException(public val osName: String) :
    SpectreAttachException(
        "The Spectre agent transport requires native AF_UNIX socket support. This JVM reports " +
            "os.name='$osName' without it. On Windows, native AF_UNIX requires Windows 10 " +
            "version 1803 / Windows Server 2019 or newer."
    )

/**
 * Thrown when the agent failed to come up at the configured UDS path within
 * [AttachOptions.attachTimeoutMs].
 *
 * If you hit this exception, the most likely causes (in order) are:
 * 1. `VirtualMachine.loadAgent` already failed for an actionable reason — check the target JVM's
 *    stderr for `[spectre-agent]` lines or an `AgentInitializationException`. The agent throws on
 *    bootstrap failures so the cause should usually surface there.
 * 2. The target JVM crashed mid-bootstrap. Check the target process is still alive.
 * 3. The UDS bind failed (path too long, permission issue) — see target's stderr.
 */
@ExperimentalSpectreAgentApi
public class AgentBootstrapTimeoutException(udsPath: java.nio.file.Path, timeoutMs: Long) :
    SpectreAttachException(
        "Agent runtime did not bind UDS path $udsPath within ${timeoutMs} ms. Check the " +
            "target JVM's stderr for `[spectre-agent]` diagnostic lines or an " +
            "AgentInitializationException with the underlying cause."
    )

/**
 * Thrown when the target JVM is owned by a different OS user than the attacher.
 *
 * The JDK Attach API requires compatible same-user ownership on POSIX. We pre-check this with
 * `ProcessHandle.of(pid).info().user()`, which reports user names rather than numeric UIDs, because
 * the underlying error from `VirtualMachine.attach` is generic and hard to diagnose.
 */
@ExperimentalSpectreAgentApi
public class AttachPermissionDeniedException(targetPid: Long, targetUser: String?) :
    SpectreAttachException(
        "Target JVM (pid=$targetPid) is owned by " +
            "${targetUser?.let { "user '$it'" } ?: "a different user"} but this process is " +
            "running as '${System.getProperty("user.name")}'. The JDK Attach API only works " +
            "across processes owned by the same OS user on POSIX systems."
    )

/**
 * Thrown when the agent JAR could not be located by [AgentAttach.attach]. Caller can fix by passing
 * [AttachOptions.agentJarPath] explicitly or by setting the
 * `dev.sebastiano.spectre.agent.runtimeJar` system property.
 */
@ExperimentalSpectreAgentApi
public class AgentJarNotFoundException(searched: List<java.nio.file.Path>) :
    SpectreAttachException(
        "Could not locate the Spectre agent runtime JAR. Searched:\n" +
            searched.joinToString("\n") { "  - $it" } +
            "\n\nAdd the `spectre-agent-runtime` jar to the attacher's runtime classpath, " +
            "run `./gradlew :agent-runtime:jar`, or pass " +
            "AttachOptions(agentJarPath = ...)."
    )

/**
 * Thrown when the attach process was interrupted (typically from cooperative cancellation: a test
 * runner cancelling a long-running fixture, or an interactive caller pressing Ctrl-C). Distinct
 * from [AgentBootstrapTimeoutException] (which means "the agent never came up") and from a generic
 * connect failure (which would point at the wrong root cause). The thread's interrupt status is
 * preserved when this is thrown, so well-behaved callers can re-check it.
 */
@ExperimentalSpectreAgentApi
public class AttachInterruptedException(udsPath: java.nio.file.Path, cause: InterruptedException) :
    SpectreAttachException(
        "Attach was interrupted while waiting for the agent's UDS at $udsPath. The thread's " +
            "interrupt status has been preserved.",
        cause,
    )

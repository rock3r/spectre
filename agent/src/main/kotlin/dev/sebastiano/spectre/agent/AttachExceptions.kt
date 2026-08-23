package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.UdsPathLimits
import java.nio.file.Path

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
            "AgentInitializationException with the underlying cause. If the runtime JAR was " +
            "chosen explicitly (AttachOptions.agentJarPath or the runtimeJar system property), " +
            "check it matches this library: a runtime older than structured agent arguments " +
            "treats the whole argument string as the socket path and binds somewhere else."
    )

/**
 * Thrown when the target JVM is owned by a different OS user than the attacher.
 *
 * The JDK Attach API requires compatible same-user ownership on POSIX. On POSIX hosts we prefer
 * numeric UID comparison when both sides resolve, and fall back to `ProcessHandle` usernames
 * otherwise (#166). The underlying error from `VirtualMachine.attach` is generic and hard to
 * diagnose, so this preflight surfaces a structured ownership message first.
 *
 * Optional [currentUser], [targetUid], and [currentUid] improve diagnostics; the two-argument form
 * remains source-compatible for existing call sites.
 */
@ExperimentalSpectreAgentApi
public class AttachPermissionDeniedException(
    targetPid: Long,
    targetUser: String?,
    currentUser: String? = System.getProperty("user.name"),
    targetUid: Long? = null,
    currentUid: Long? = null,
) :
    SpectreAttachException(
        buildAttachPermissionDeniedMessage(
            targetPid = targetPid,
            targetUser = targetUser,
            currentUser = currentUser,
            targetUid = targetUid,
            currentUid = currentUid,
        )
    )

private fun buildAttachPermissionDeniedMessage(
    targetPid: Long,
    targetUser: String?,
    currentUser: String?,
    targetUid: Long?,
    currentUid: Long?,
): String {
    val targetDesc = formatOwner(targetUser, targetUid)
    val currentDesc = formatOwner(currentUser, currentUid)
    return "Target JVM (pid=$targetPid) is owned by $targetDesc but this process is " +
        "running as $currentDesc. The JDK Attach API only works across processes owned by " +
        "the same OS user identity (numeric UID on POSIX when available)."
}

private fun formatOwner(userName: String?, uid: Long?): String =
    when {
        uid != null && userName != null -> "uid=$uid (user '$userName')"
        uid != null -> "uid=$uid"
        userName != null -> "user '$userName'"
        else -> "a different user"
    }

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
 * Thrown when more than one agent runtime JAR candidate is found during discovery.
 *
 * Attach refuses to guess which jar to load: classpath order and directory listing order are not
 * stable selection keys. Pass [AttachOptions.agentJarPath] or set the
 * `dev.sebastiano.spectre.agent.runtimeJar` system property to choose explicitly.
 */
@ExperimentalSpectreAgentApi
public class AmbiguousAgentRuntimeJarException(public val candidates: List<java.nio.file.Path>) :
    SpectreAttachException(
        "Multiple Spectre agent runtime JARs found; refuse to guess which to load:\n" +
            candidates.joinToString("\n") { "  - $it" } +
            "\n\nPass AttachOptions(agentJarPath = ...) or set " +
            "dev.sebastiano.spectre.agent.runtimeJar to select one explicitly."
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

/**
 * Thrown when a Unix Domain Socket path is longer than the platform's `sockaddr_un.sun_path` can
 * hold (102 usable bytes on macOS, 106 on Linux and Windows — the JDK reserves two bytes of the
 * 104/108-byte field, not one; see `UdsPathLimits`).
 *
 * The kernel rejects such a path at `bind` time, and because the agent binds inside the *target*
 * JVM the caller would otherwise see only "agent failed to initialize", with `SocketException: Unix
 * domain path too long` buried in the target's stderr (#442). Raising this on the attaching side,
 * before the agent JAR is loaded, names the offending path and the limit.
 *
 * @property candidates the path(s) that were too long — one when the caller passed
 *   [AttachOptions.udsPath] explicitly, one per base directory Spectre tried when it was picking
 *   the default.
 */
@ExperimentalSpectreAgentApi
public class UdsPathTooLongException(public val candidates: List<Path>) :
    SpectreAttachException(
        "No usable Unix domain socket path: this platform's sockaddr_un.sun_path holds at most " +
            "${UdsPathLimits.maxPathBytes} bytes. Tried:\n" +
            candidates.joinToString("\n") { "  - $it (${UdsPathLimits.byteLength(it)} bytes)" } +
            "\n\nPass AttachOptions(udsPath = ...) pointing at a shorter path — a directory close " +
            "to the filesystem root keeps the most room for the socket name."
    )

package dev.sebastiano.spectre.agent

/**
 * Same-user preflight for [AgentAttach.attach], expressed as a per-platform seam so the ownership
 * check can differ by OS without the caller branching on `os.name`.
 *
 * The JDK Attach API only rendezvous across processes owned by the same OS user (on POSIX the
 * `/tmp/.java_pid<pid>` file is unreadable otherwise; on Windows the named-pipe handshake is
 * ACL-scoped to the owner). The underlying `VirtualMachine.attach` error is generic and hard to
 * diagnose, so we pre-check ownership and throw a dedicated [AttachPermissionDeniedException] with
 * a clear message before opening the VM connection.
 *
 * **Advisory, not authoritative.** The real boundary is the OS. When ownership cannot be determined
 * (sandboxes return an empty `ProcessHandle.user()`), the preflight proceeds and lets the attach
 * surface whatever the OS reports.
 *
 * Both implementations resolve the current *and* target owner through the same
 * `ProcessHandle.info().user()` API so the two strings are directly comparable. The previous
 * implementation compared `ProcessHandle.user()` (which is `DOMAIN\name` on Windows) against
 * `System.getProperty("user.name")` (bare) — a mismatch that wrongly rejected the same user
 * attaching to their own JVM on Windows.
 *
 * The per-platform split leaves room for #166 (numeric POSIX UID comparison) and a future Windows
 * SID/elevation refinement to drop in without reshaping call sites.
 */
@ExperimentalSpectreAgentApi
internal interface AttachUserPreflight {
    /**
     * Throws [AttachPermissionDeniedException] when [targetPid] is owned by a different OS user
     * than the current process. Returns normally when the users match or ownership is unknown.
     */
    fun requireSameUser(targetPid: Long)

    companion object {
        /** Select the preflight implementation for [osName]. */
        fun forOs(osName: String = System.getProperty("os.name").orEmpty()): AttachUserPreflight =
            if (osName.startsWith("Windows", ignoreCase = true)) WindowsUserPreflight()
            else PosixUserPreflight()
    }
}

/**
 * POSIX same-user preflight: case-sensitive username equality via `ProcessHandle.info().user()`.
 *
 * #166 will refine this to a numeric UID comparison (name equality can false-negative under
 * directory services, domain prefixes, or localized name formatting) and keep the name path as a
 * fallback when a numeric UID is unavailable.
 */
@ExperimentalSpectreAgentApi
internal class PosixUserPreflight(
    private val currentUser: () -> String? = defaultCurrentUser,
    private val targetUser: (Long) -> String? = defaultTargetUser,
) : AttachUserPreflight {
    override fun requireSameUser(targetPid: Long) {
        requireSameUserOwnership(targetPid, currentUser(), targetUser(targetPid)) { it }
    }
}

/**
 * Windows same-user preflight: case-insensitive equality of the `DOMAIN\name` form that
 * `ProcessHandle.info().user()` returns on Windows (usernames and domains are case-insensitive).
 *
 * Two same-user processes at *different* integrity levels (elevated vs non-elevated) share the same
 * account and therefore pass this preflight; Windows may still deny the OS-level attach across
 * integrity levels, which surfaces from `VirtualMachine.attach` itself. The preflight is advisory.
 */
@ExperimentalSpectreAgentApi
internal class WindowsUserPreflight(
    private val currentUser: () -> String? = defaultCurrentUser,
    private val targetUser: (Long) -> String? = defaultTargetUser,
) : AttachUserPreflight {
    override fun requireSameUser(targetPid: Long) {
        requireSameUserOwnership(targetPid, currentUser(), targetUser(targetPid)) { it.lowercase() }
    }
}

private inline fun requireSameUserOwnership(
    targetPid: Long,
    current: String?,
    target: String?,
    normalize: (String) -> String,
) {
    // Undeterminable ownership must not block the attach — the OS remains the real boundary.
    if (current == null || target == null) return
    if (normalize(current) != normalize(target)) {
        @OptIn(ExperimentalSpectreAgentApi::class)
        throw AttachPermissionDeniedException(targetPid, target)
    }
}

private val defaultCurrentUser: () -> String? = {
    ProcessHandle.current().info().user().orElse(null)
}

private val defaultTargetUser: (Long) -> String? = { pid ->
    ProcessHandle.of(pid).flatMap { it.info().user() }.orElse(null)
}

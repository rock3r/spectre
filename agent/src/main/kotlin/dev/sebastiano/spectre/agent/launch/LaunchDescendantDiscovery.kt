package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreProcesses
import kotlin.streams.asSequence

/**
 * Locates the real app JVM among a Gradle client's descendants for readiness and teardown.
 *
 * Never returns a JVM whose display name looks like a Gradle daemon — teardown must not kill the
 * daemon. Never returns an arbitrary machine-wide JVM when no descendants are known yet — that
 * would risk attaching to / killing an unrelated process.
 */
@ExperimentalSpectreAgentApi
public object LaunchDescendantDiscovery {

    /**
     * Find an attachable JVM that is a **ProcessHandle descendant** of [clientPid].
     *
     * Returns null when:
     * - the client handle is missing,
     * - no descendants are visible yet (keep polling),
     * - no listed JVM is both a descendant and non-daemon (and matches [nameFilter] if set).
     *
     * Does **not** fall back to “any JVM on the machine.”
     */
    public fun discoverAppJvm(clientPid: Long, nameFilter: String?): Long? {
        val clientHandle = ProcessHandle.of(clientPid).orElse(null) ?: return null
        val descendantPids: Set<Long> =
            try {
                clientHandle.descendants().asSequence().map { it.pid() }.toSet()
            } catch (_: UnsupportedOperationException) {
                emptySet()
            }
        if (descendantPids.isEmpty()) return null

        val listed = runCatching { SpectreProcesses.listJvmProcesses() }.getOrDefault(emptyList())
        val candidates = listed.filter { info ->
            info.pid in descendantPids &&
                !isGradleDaemonDisplayName(info.displayName) &&
                (nameFilter == null || info.displayName.contains(nameFilter, ignoreCase = true))
        }
        // Prefer the highest PID among matches (typically the most recently spawned app JVM).
        return candidates.maxByOrNull { it.pid }?.pid
    }

    /** True when [displayName] looks like a Gradle daemon JVM banner from `jps` / Attach list. */
    public fun isGradleDaemonDisplayName(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return lower.contains("gradle daemon") ||
            lower.contains("gradledaemon") ||
            lower.contains("org.gradle.launcher.daemon") ||
            lower.contains("gradle-daemon")
    }

    /**
     * True when [pid] is visible to the Attach API via `VirtualMachine.list()`.
     *
     * Intentionally does **not** treat a mere live `java` ProcessHandle as attach-ready — that
     * races with hsperfdata registration and turns early attach failures into bootstrap errors
     * instead of waiting out the JVM-attachable stage.
     */
    internal fun isJvmAttachable(pid: Long): Boolean {
        val listed = runCatching { SpectreProcesses.listJvmProcesses() }.getOrDefault(emptyList())
        return listed.any { it.pid == pid }
    }
}

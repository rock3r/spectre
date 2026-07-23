package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.JvmProcessInfo
import dev.sebastiano.spectre.agent.SpectreProcesses
import kotlin.streams.asSequence

/**
 * Locates the real app JVM for Gradle-ish launches (readiness + teardown).
 *
 * Layout reality: `./gradlew :app:run` typically has the **Gradle daemon** spawn the app JVM, so
 * the app is **not** a ProcessHandle descendant of the gradlew client. Discovery therefore
 * combines:
 * 1. optional [nameFilter] against [JvmProcessInfo.displayName] (required for daemon-child apps
 *    when the client graph does not contain the app)
 * 2. JVMs parented by a known Gradle daemon (from the attach list)
 * 3. ProcessHandle descendants of the client (covers `--no-daemon` / rare layouts)
 *
 * Never returns a Gradle daemon display-name match, and never falls back to an arbitrary
 * machine-wide JVM without one of the signals above.
 */
@ExperimentalSpectreAgentApi
public object LaunchDescendantDiscovery {

    /**
     * Find an attachable app JVM for a Gradle-ish launch started as [clientPid].
     *
     * @param nameFilter optional case-insensitive substring of the app's main-class display name.
     *   Strongly recommended for multi-project machines. When set and no listed JVM matches,
     *   returns null (does not fall through to unrelated JVMs).
     */
    public fun discoverAppJvm(clientPid: Long, nameFilter: String?): Long? {
        val listed = runCatching { SpectreProcesses.listJvmProcesses() }.getOrDefault(emptyList())
        if (listed.isEmpty()) return null

        val daemonPids =
            listed.filter { isGradleDaemonDisplayName(it.displayName) }.map { it.pid }.toSet()
        val nonDaemon = listed.filter { info ->
            info.pid != clientPid && !isGradleDaemonDisplayName(info.displayName)
        }
        if (nonDaemon.isEmpty()) return null

        val clientDescendants = descendantPidsOf(clientPid)
        val childOfDaemon = nonDaemon.filter { info ->
            val parent = parentPid(info.pid) ?: return@filter false
            parent in daemonPids
        }

        if (!nameFilter.isNullOrBlank()) {
            val nameMatched = nonDaemon.filter {
                it.displayName.contains(nameFilter, ignoreCase = true)
            }
            if (nameMatched.isEmpty()) return null
            // Restrict to structural signals only — never fall back to an arbitrary
            // machine-wide name match (could be another developer's IDE / leftover process).
            val structuralNameMatches = nameMatched.filter { info ->
                info.pid in childOfDaemon.map(JvmProcessInfo::pid).toSet() ||
                    info.pid in clientDescendants
            }
            return structuralNameMatches.maxByOrNull { it.pid }?.pid
        }

        // No name filter: only structural signals (never "any non-daemon JVM").
        val structural = childOfDaemon + nonDaemon.filter { it.pid in clientDescendants }
        return structural.distinctBy { it.pid }.maxByOrNull { it.pid }?.pid
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

    private fun descendantPidsOf(clientPid: Long): Set<Long> {
        val clientHandle = ProcessHandle.of(clientPid).orElse(null) ?: return emptySet()
        return try {
            clientHandle.descendants().asSequence().map { it.pid() }.toSet()
        } catch (_: UnsupportedOperationException) {
            emptySet()
        }
    }

    private fun parentPid(pid: Long): Long? =
        ProcessHandle.of(pid).flatMap { it.parent() }.map { it.pid() }.orElse(null)
}

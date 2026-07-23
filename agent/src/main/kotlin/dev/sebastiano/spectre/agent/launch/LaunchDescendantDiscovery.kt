package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.JvmProcessInfo
import dev.sebastiano.spectre.agent.SpectreProcesses
import kotlin.streams.asSequence

/**
 * Locates the real app JVM for Gradle-ish launches (readiness + teardown).
 *
 * Layout reality: `./gradlew :app:run` typically has the **Gradle daemon** spawn the app JVM, so
 * the app is **not** a ProcessHandle descendant of the gradlew client.
 *
 * Safety rules:
 * - Never returns a Gradle daemon display-name match.
 * - Never returns an arbitrary machine-wide JVM.
 * - Daemon-child candidates are only considered when [nameFilter] is set (shared daemons often host
 *   unrelated app JVMs).
 * - Without [nameFilter], only ProcessHandle descendants of the client are considered
 *   (`--no-daemon` and rare layouts).
 */
@ExperimentalSpectreAgentApi
public object LaunchDescendantDiscovery {

    /**
     * Find an attachable app JVM for a Gradle-ish launch started as [clientPid].
     *
     * @param nameFilter case-insensitive substring of the app's main-class display name. Required
     *   to safely pick among daemon children on machines with concurrent Gradle apps.
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
            val structural = nameMatched.filter { info ->
                info.pid in childOfDaemon.map(JvmProcessInfo::pid).toSet() ||
                    info.pid in clientDescendants
            }
            return structural.maxByOrNull { it.pid }?.pid
        }

        // No name filter: only client descendants (never unfiltered daemon children).
        return nonDaemon.filter { it.pid in clientDescendants }.maxByOrNull { it.pid }?.pid
    }

    /** True when [displayName] looks like a Gradle daemon JVM banner from `jps` / Attach list. */
    public fun isGradleDaemonDisplayName(displayName: String): Boolean {
        val lower = displayName.lowercase()
        return lower.contains("gradle daemon") ||
            lower.contains("gradledaemon") ||
            lower.contains("org.gradle.launcher.daemon") ||
            lower.contains("gradle-daemon")
    }

    /** True when [pid] is visible to the Attach API via `VirtualMachine.list()`. */
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

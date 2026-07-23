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
            // Prefer client descendants over arbitrary daemon children so concurrent Gradle
            // apps with the same main class are not stolen from another launch.
            nameMatched
                .filter { it.pid in clientDescendants }
                .maxByOrNull { it.pid }
                ?.pid
                ?.let {
                    return it
                }
            nameMatched
                .filter { it.pid in childOfDaemon.map(JvmProcessInfo::pid).toSet() }
                .maxByOrNull { it.pid }
                ?.pid
                ?.let {
                    return it
                }
            // Native process-tree fallback: hsperfdata/list can lag behind spawn.
            return discoverByNativeTree(
                clientPid = clientPid,
                daemonPids = daemonPids,
                nameFilter = nameFilter,
            )
        }

        // No name filter: only client descendants (never unfiltered daemon children).
        return nonDaemon.filter { it.pid in clientDescendants }.maxByOrNull { it.pid }?.pid
            ?: discoverByNativeTree(
                clientPid = clientPid,
                daemonPids = emptySet(), // no unfiltered daemon walk without nameFilter
                nameFilter = null,
            )
    }

    /**
     * Walk [ProcessHandle] descendants when Attach list is incomplete. With [nameFilter], also walk
     * daemon children; without it, only [clientPid] descendants.
     */
    private fun discoverByNativeTree(
        clientPid: Long,
        daemonPids: Set<Long>,
        nameFilter: String?,
    ): Long? {
        val roots = buildList {
            add(clientPid)
            if (!nameFilter.isNullOrBlank()) {
                addAll(daemonPids)
            }
        }
        val daemonPidSet = daemonPids // roots may include daemon pids as walk roots only
        return roots
            .asSequence()
            .flatMap { root -> descendantPidsOf(root).asSequence() }
            .filter { pid -> pid != clientPid }
            .filter { pid -> pid !in daemonPidSet }
            .filter { pid -> looksLikeJavaProcess(pid) }
            .filter { pid -> !commandLineLooksLikeGradleDaemon(pid) }
            .filter { pid -> nameFilter.isNullOrBlank() || commandLineContains(pid, nameFilter) }
            .maxOrNull()
    }

    private fun commandLineLooksLikeGradleDaemon(pid: Long): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        val cmd = handle.info().command().orElse("")
        val args = handle.info().arguments().orElse(emptyArray()).joinToString(" ")
        return isGradleDaemonDisplayName("$cmd $args")
    }

    private fun looksLikeJavaProcess(pid: Long): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        if (!handle.isAlive) return false
        val cmd = handle.info().command().orElse("")
        val base = LaunchCommandRewriter.basename(cmd)
        return base.equals("java", ignoreCase = true) || base.equals("java.exe", ignoreCase = true)
    }

    private fun commandLineContains(pid: Long, needle: String): Boolean {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return false
        val cmd = handle.info().command().orElse("")
        val args = handle.info().arguments().orElse(emptyArray()).joinToString(" ")
        return cmd.contains(needle, ignoreCase = true) || args.contains(needle, ignoreCase = true)
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

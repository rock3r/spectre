package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.JvmProcessInfo
import dev.sebastiano.spectre.agent.SpectreProcesses
import java.time.Instant
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
 * - Never returns a JVM that was already running when the launch began — it belongs to something
 *   else, however well its main class matches (#446). See [predatesLaunch].
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
     *
     * Opt-in diagnostics (#458): set
     * `-Ddev.sebastiano.spectre.agent.launch.discoveryDiagnostics=true` or
     * `SPECTRE_LAUNCH_DISCOVERY_DIAGNOSTICS=true` to print each selection's candidate list, start
     * instants, launch boundary, and rejection reasons to stderr. Off by default; selection is
     * unchanged.
     */
    public fun discoverAppJvm(
        clientPid: Long,
        nameFilter: String?,
        /**
         * When the launch began, used to reject JVMs that predate it (see [predatesLaunch]).
         *
         * Callers that poll must capture this **once, while the client is still alive** and pass
         * the same value every time. Gradle-ish discovery deliberately keeps running after the
         * client exits, and a reaped pid no longer resolves to a [ProcessHandle] — so re-deriving
         * the boundary per poll would silently fail open exactly when the gate matters most.
         */
        clientStart: Instant? = processStartInstant(clientPid),
    ): Long? =
        selectAppJvm(
            clientPid = clientPid,
            nameFilter = nameFilter,
            clientStart = clientStart,
            onDiagnostics = AppJvmDiscoveryDiagnostics.sinkFromEnvironment(),
        )

    /**
     * [discoverAppJvm] with its process facts injectable, so the selection rules can be tested
     * against a fixed process layout instead of whatever happens to be running (#446).
     */
    internal fun selectAppJvm(
        clientPid: Long,
        nameFilter: String?,
        clientStart: Instant?,
        listed: List<JvmProcessInfo> =
            runCatching { SpectreProcesses.listJvmProcesses() }.getOrDefault(emptyList()),
        descendantsOf: (Long) -> Set<Long> = ::descendantPidsOf,
        parentOf: (Long) -> Long? = ::parentPid,
        startInstantOf: (Long) -> Instant? = ::processStartInstant,
        nativeFallback: (Long, Set<Long>, String?) -> Long? = { client, daemons, filter ->
            discoverByNativeTree(client, daemons, filter, clientStart, startInstantOf)
        },
        onDiagnostics: AppJvmDiscoveryDiagnosticSink = AppJvmDiscoveryDiagnosticSink.NoOp,
    ): Long? {
        // Off by default: do not construct a record or extra start-instant lookups (#458).
        val diagnosticsOn = onDiagnostics !== AppJvmDiscoveryDiagnosticSink.NoOp
        val startOf = AppJvmDiscoveryDiagnostics.startLookup(diagnosticsOn, startInstantOf)
        val selected =
            chooseAppJvm(
                clientPid = clientPid,
                nameFilter = nameFilter,
                clientStart = clientStart,
                listed = listed,
                descendantsOf = descendantsOf,
                parentOf = parentOf,
                startInstantOf = startOf,
                nativeFallback = nativeFallback,
            )
        if (diagnosticsOn) {
            emitDiscoveryRecord(
                onDiagnostics = onDiagnostics,
                clientPid = clientPid,
                nameFilter = nameFilter,
                clientStart = clientStart,
                listed = listed,
                startInstantOf = startOf,
                selectedPid = selected,
            )
        }
        return selected
    }

    private fun chooseAppJvm(
        clientPid: Long,
        nameFilter: String?,
        clientStart: Instant?,
        listed: List<JvmProcessInfo>,
        descendantsOf: (Long) -> Set<Long>,
        parentOf: (Long) -> Long?,
        startInstantOf: (Long) -> Instant?,
        nativeFallback: (Long, Set<Long>, String?) -> Long?,
    ): Long? {
        if (listed.isEmpty()) return null

        val daemonPids =
            listed.filter { isGradleDaemonDisplayName(it.displayName) }.map { it.pid }.toSet()
        val nonDaemon = listed.filter { info ->
            info.pid != clientPid &&
                !isGradleDaemonDisplayName(info.displayName) &&
                // A JVM that was already running when this launch started belongs to something
                // else — a leftover fixture, or a sibling e2e sharing the daemon (#446).
                !predatesLaunch(startInstantOf(info.pid), clientStart)
        }
        if (nonDaemon.isEmpty()) {
            // Every Attach-visible JVM is a daemon or predates the launch. That does not mean
            // the target is absent: VirtualMachine.list() lags, and -XX:-UsePerfData hides a
            // JVM entirely. The native walk is the fallback, so do not skip it (#446).
            val walkRoots = if (nameFilter.isNullOrBlank()) emptySet() else daemonPids
            return nativeFallback(clientPid, walkRoots, nameFilter)
        }

        val clientDescendants = descendantsOf(clientPid)
        val childOfDaemon = nonDaemon.filter { info ->
            val parent = parentOf(info.pid) ?: return@filter false
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
            return nativeFallback(clientPid, daemonPids, nameFilter)
        }

        // No name filter: only client descendants (never unfiltered daemon children).
        return nonDaemon.filter { it.pid in clientDescendants }.maxByOrNull { it.pid }?.pid
            // No unfiltered daemon walk without nameFilter.
            ?: nativeFallback(clientPid, emptySet(), null)
    }

    private fun emitDiscoveryRecord(
        onDiagnostics: AppJvmDiscoveryDiagnosticSink,
        clientPid: Long,
        nameFilter: String?,
        clientStart: Instant?,
        listed: List<JvmProcessInfo>,
        startInstantOf: (Long) -> Instant?,
        selectedPid: Long?,
    ) {
        onDiagnostics.emit(
            AppJvmDiscoveryRecord(
                clientPid = clientPid,
                nameFilter = nameFilter,
                launchBoundary = clientStart,
                candidates =
                    listed.map { info ->
                        val start = startInstantOf(info.pid)
                        AppJvmDiscoveryCandidate(
                            pid = info.pid,
                            displayName = info.displayName,
                            startInstant = start,
                            rejectionReason =
                                listedRejectionReason(
                                    pid = info.pid,
                                    displayName = info.displayName,
                                    clientPid = clientPid,
                                    clientStart = clientStart,
                                    candidateStart = start,
                                ),
                        )
                    },
                selectedPid = selectedPid,
            )
        )
    }

    private fun listedRejectionReason(
        pid: Long,
        displayName: String,
        clientPid: Long,
        clientStart: Instant?,
        candidateStart: Instant?,
    ): String? =
        when {
            pid == clientPid -> AppJvmDiscoveryDiagnostics.REASON_CLIENT_PID
            isGradleDaemonDisplayName(displayName) ->
                AppJvmDiscoveryDiagnostics.REASON_GRADLE_DAEMON
            predatesLaunch(candidateStart, clientStart) ->
                AppJvmDiscoveryDiagnostics.REASON_PREDATES_LAUNCH
            else -> null
        }

    /**
     * True when [candidateStart] proves the candidate JVM was already running before the launch
     * began at [clientStart], so it cannot be the JVM this launch started.
     *
     * Fails open. Not every platform and permission setup exposes process start times, and losing
     * discovery entirely would be far worse than the race this guards — so an unknown instant on
     * either side keeps the candidate. Equal instants keep it too: process clocks are coarse.
     */
    internal fun predatesLaunch(candidateStart: Instant?, clientStart: Instant?): Boolean {
        if (candidateStart == null || clientStart == null) return false
        return candidateStart.isBefore(clientStart)
    }

    /** Best-effort process start time; empty on hosts or permission setups that hide it. */
    internal fun processStartInstant(pid: Long): Instant? =
        ProcessHandle.of(pid).flatMap { it.info().startInstant() }.orElse(null)

    /**
     * Walk [ProcessHandle] descendants when Attach list is incomplete. With [nameFilter], also walk
     * daemon children; without it, only [clientPid] descendants.
     */
    private fun discoverByNativeTree(
        clientPid: Long,
        daemonPids: Set<Long>,
        nameFilter: String?,
        clientStart: Instant?,
        startInstantOf: (Long) -> Instant?,
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
            // Same rule as the listed-JVM path: this launch cannot have started a JVM that was
            // already running when it began (#446).
            .filter { pid -> !predatesLaunch(startInstantOf(pid), clientStart) }
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

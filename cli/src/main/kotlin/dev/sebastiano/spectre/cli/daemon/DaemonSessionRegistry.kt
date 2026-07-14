package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAttachException

/** In-memory session table for one daemon process. */
@OptIn(ExperimentalSpectreAgentApi::class)
public class DaemonSessionRegistry(
    private val attachAutomator: (Long) -> AutoCloseable = { targetPid ->
        AgentAttach.attach(targetPid)
    }
) : AutoCloseable {
    private val sessionsByPid: MutableMap<Long, DaemonSession> = linkedMapOf()

    public val isShutdown: Boolean
        get() = shutdown

    private var shutdown: Boolean = false

    @Synchronized
    public fun handle(request: DaemonRequest): DaemonResponse =
        when (request) {
            is DaemonRequest.Hello ->
                DaemonResponse.Hello(daemonVersion = DaemonProtocol.CurrentVersion)
            is DaemonRequest.Attach -> attach(request.targetPid)
            is DaemonRequest.Detach -> detach(request.sessionId)
            DaemonRequest.ListSessions -> listSessions()
            DaemonRequest.Shutdown -> shutdown()
        }

    private fun attach(targetPid: Long): DaemonResponse {
        if (shutdown) {
            return DaemonResponse.Error(
                code = DaemonErrorCode.ShutdownInProgress,
                message = "daemon shutdown is already in progress",
            )
        }
        sessionsByPid[targetPid]?.let { session ->
            return DaemonResponse.Attached(
                sessionId = session.summary.sessionId,
                targetPid = session.summary.targetPid,
            )
        }
        val attached =
            try {
                attachAutomator(targetPid)
            } catch (exception: SpectreAttachException) {
                return DaemonResponse.Error(
                    code = DaemonErrorCode.AttachFailed,
                    message = exception.message ?: "Failed to attach to process $targetPid",
                )
            }
        val session = DaemonSession(summary = targetPid.toSessionSummary(), automator = attached)
        sessionsByPid[targetPid] = session
        return DaemonResponse.Attached(
            sessionId = session.summary.sessionId,
            targetPid = session.summary.targetPid,
        )
    }

    private fun detach(sessionId: String): DaemonResponse {
        val removed =
            sessionsByPid.entries.firstOrNull { (_, session) -> session.sessionId == sessionId }
                ?: return DaemonResponse.Error(
                    code = DaemonErrorCode.SessionNotFound,
                    message = "session not found: $sessionId",
                )
        sessionsByPid.remove(removed.key)?.automator?.close()
        return DaemonResponse.Detached(sessionId = sessionId)
    }

    private fun listSessions(): DaemonResponse =
        DaemonResponse.Sessions(
            sessions = sessionsByPid.values.map { it.summary }.sortedBy { it.targetPid }
        )

    private fun shutdown(): DaemonResponse {
        close()
        return DaemonResponse.ShuttingDown
    }

    @Synchronized
    override fun close() {
        shutdown = true
        sessionsByPid.values.forEach { session -> session.automator.close() }
        sessionsByPid.clear()
    }

    private fun Long.toSessionSummary(): DaemonSessionSummary =
        DaemonSessionSummary(sessionId = "pid-$this", targetPid = this)

    private data class DaemonSession(
        val summary: DaemonSessionSummary,
        val automator: AutoCloseable,
    ) {
        val sessionId: String
            get() = summary.sessionId
    }
}

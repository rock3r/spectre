package dev.sebastiano.spectre.cli.daemon

/** In-memory session table for one daemon process. */
public class DaemonSessionRegistry {
    private val sessionsByPid: MutableMap<Long, DaemonSessionSummary> = linkedMapOf()

    public val isShutdown: Boolean
        get() = shutdown

    private var shutdown: Boolean = false

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
        val session = sessionsByPid.getOrPut(targetPid) { targetPid.toSessionSummary() }
        return DaemonResponse.Attached(sessionId = session.sessionId, targetPid = session.targetPid)
    }

    private fun detach(sessionId: String): DaemonResponse {
        val removed =
            sessionsByPid.entries.firstOrNull { (_, session) -> session.sessionId == sessionId }
                ?: return DaemonResponse.Error(
                    code = DaemonErrorCode.SessionNotFound,
                    message = "session not found: $sessionId",
                )
        sessionsByPid.remove(removed.key)
        return DaemonResponse.Detached(sessionId = sessionId)
    }

    private fun listSessions(): DaemonResponse =
        DaemonResponse.Sessions(sessions = sessionsByPid.values.sortedBy { it.targetPid })

    private fun shutdown(): DaemonResponse {
        shutdown = true
        sessionsByPid.clear()
        return DaemonResponse.ShuttingDown
    }

    private fun Long.toSessionSummary(): DaemonSessionSummary =
        DaemonSessionSummary(sessionId = "pid-$this", targetPid = this)
}

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAttachException
import java.io.IOException

/** In-memory session table for one daemon process. */
@OptIn(ExperimentalSpectreAgentApi::class)
public class DaemonSessionRegistry
internal constructor(
    private val jvmProcessDiscovery: DaemonJvmProcessDiscovery = DaemonJvmProcessDiscovery(),
    private val attachAutomator: (Long) -> DaemonSessionAutomator = { targetPid ->
        AttachedDaemonSession(AgentAttach.attach(targetPid))
    },
) : AutoCloseable {
    private val sessionsByPid: MutableMap<Long, DaemonSession> = linkedMapOf()

    public val isShutdown: Boolean
        get() = shutdown

    public val hasSessions: Boolean
        @Synchronized get() = sessionsByPid.isNotEmpty()

    private var shutdown: Boolean = false

    @Synchronized
    public fun handle(request: DaemonRequest): DaemonResponse =
        when (request) {
            is DaemonRequest.Hello ->
                DaemonResponse.Hello(daemonVersion = DaemonProtocol.CurrentVersion)
            is DaemonRequest.Attach -> attach(request.targetPid)
            is DaemonRequest.Detach -> detach(request.sessionId)
            DaemonRequest.ListSessions -> listSessions()
            is DaemonRequest.ListJvmProcesses -> jvmProcessDiscovery.list(request.requesterPid)
            is DaemonRequest.Windows -> windows(request.sessionId)
            is DaemonRequest.AllNodes -> allNodes(request.sessionId)
            is DaemonRequest.FindByTestTag -> findByTestTag(request.sessionId, request.tag)
            is DaemonRequest.Click -> click(request.sessionId, request.nodeKey)
            is DaemonRequest.TypeText -> typeText(request.sessionId, request.text)
            is DaemonRequest.Screenshot -> screenshot(request.sessionId)
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

    private fun windows(sessionId: String): DaemonResponse =
        invoke(sessionId) { automator ->
            DaemonResponse.Windows(sessionId = sessionId, windows = automator.windows())
        }

    private fun allNodes(sessionId: String): DaemonResponse =
        invoke(sessionId) { automator ->
            DaemonResponse.Nodes(sessionId = sessionId, nodes = automator.allNodes())
        }

    private fun findByTestTag(sessionId: String, tag: String): DaemonResponse =
        invoke(sessionId) { automator ->
            DaemonResponse.Nodes(sessionId = sessionId, nodes = automator.findByTestTag(tag))
        }

    private fun click(sessionId: String, nodeKey: String): DaemonResponse =
        invoke(sessionId) { automator ->
            automator.click(nodeKey)
            DaemonResponse.Completed(sessionId)
        }

    private fun typeText(sessionId: String, text: String): DaemonResponse =
        invoke(sessionId) { automator ->
            automator.typeText(text)
            DaemonResponse.Completed(sessionId)
        }

    private fun screenshot(sessionId: String): DaemonResponse =
        invoke(sessionId) { automator ->
            DaemonResponse.Screenshot(sessionId = sessionId, pngBytes = automator.screenshot())
        }

    private fun invoke(
        sessionId: String,
        operation: (DaemonSessionAutomator) -> DaemonResponse,
    ): DaemonResponse {
        val session =
            sessionsByPid.values.firstOrNull { it.sessionId == sessionId }
                ?: return sessionNotFound(sessionId)
        return try {
            operation(session.automator)
        } catch (exception: IOException) {
            DaemonResponse.Error(
                code = DaemonErrorCode.OperationFailed,
                message = exception.message ?: "session operation failed",
            )
        }
    }

    private fun sessionNotFound(sessionId: String): DaemonResponse.Error =
        DaemonResponse.Error(
            code = DaemonErrorCode.SessionNotFound,
            message = "session not found: $sessionId",
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
        val automator: DaemonSessionAutomator,
    ) {
        val sessionId: String
            get() = summary.sessionId
    }
}

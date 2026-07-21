package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAttachException
import dev.sebastiano.spectre.cli.jdkPreflightError
import java.io.IOException

/** In-memory session table for one daemon process. */
@OptIn(ExperimentalSpectreAgentApi::class)
public class DaemonSessionRegistry
internal constructor(
    private val jvmProcessDiscovery: DaemonJvmProcessDiscovery = DaemonJvmProcessDiscovery(),
    private val attachAutomator: (Long) -> DaemonSessionAutomator = { targetPid ->
        installEmbeddedAgentRuntimeIfNeeded()
        val automator = AgentAttach.attach(targetPid)
        AttachedDaemonSession(delegate = automator, sessionId = "pid-$targetPid")
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
            is DaemonRequest.ListJvmProcesses ->
                listJvmProcesses(jvmProcessDiscovery, request.requesterPid)
            is DaemonRequest.Windows,
            is DaemonRequest.AllNodes,
            is DaemonRequest.FindByTestTag,
            is DaemonRequest.Click,
            is DaemonRequest.TypeText,
            is DaemonRequest.Screenshot,
            is DaemonRequest.Capture,
            is DaemonRequest.StartRecording,
            is DaemonRequest.StopRecording,
            is DaemonRequest.RecordingStatus -> handleSessionCommand(request)
            DaemonRequest.Shutdown -> shutdown()
        }

    private fun handleSessionCommand(request: DaemonRequest): DaemonResponse =
        when (request) {
            is DaemonRequest.Windows ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Windows(request.sessionId, automator.windows())
                }
            is DaemonRequest.AllNodes ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Nodes(request.sessionId, automator.allNodes())
                }
            is DaemonRequest.FindByTestTag ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Nodes(request.sessionId, automator.findByTestTag(request.tag))
                }
            is DaemonRequest.Click ->
                invoke(request.sessionId) { automator ->
                    automator.click(request.nodeKey)
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.TypeText ->
                invoke(request.sessionId) { automator ->
                    automator.typeText(request.text)
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.Screenshot ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Screenshot(request.sessionId, automator.screenshot())
                }
            is DaemonRequest.Capture ->
                invoke(request.sessionId) { automator ->
                    CaptureArtifactStore.write(
                        sessionId = request.sessionId,
                        result = automator.capture(request.windowIndex),
                        outDir = request.outDir,
                        liveSessionIds = liveSessionIds(),
                    )
                }
            is DaemonRequest.StartRecording ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.RecordingStarted(
                        request.sessionId,
                        automator.startRecording(request.outputPath, request.windowIndex),
                    )
                }
            is DaemonRequest.StopRecording ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.RecordingStopped(
                        request.sessionId,
                        automator.stopRecording(liveSessionIds()),
                    )
                }
            is DaemonRequest.RecordingStatus ->
                invoke(request.sessionId) { automator ->
                    val status = automator.recordingStatus()
                    DaemonResponse.RecordingStatus(
                        sessionId = request.sessionId,
                        active = status.active,
                        outputPath = status.outputPath,
                        captureDirectory = status.captureDirectory,
                    )
                }
            else ->
                error(
                    "handleSessionCommand received a non-session request: ${request::class.simpleName}"
                )
        }

    private fun attach(targetPid: Long): DaemonResponse {
        attachPreflightFailure()?.let {
            return it
        }
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
            } catch (exception: IOException) {
                return DaemonResponse.Error(
                    code = DaemonErrorCode.AttachFailed,
                    message = exception.message ?: "Failed to prepare the agent runtime",
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
        val remainingLive = liveSessionIds() - sessionId
        val automator = sessionsByPid.remove(removed.key)?.automator
        // Finalize recording while other sessions are still known live (#185 review).
        automator?.finalizeRecording(remainingLive)
        automator?.close()
        return CaptureSessionReport.forDetach(sessionId)
    }

    private fun liveSessionIds(): Set<String> = sessionsByPid.values.map { it.sessionId }.toSet()

    private fun listSessions(): DaemonResponse =
        DaemonResponse.Sessions(
            sessions = sessionsByPid.values.map { it.summary }.sortedBy { it.targetPid }
        )

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

private const val AGENT_RUNTIME_JAR_PROPERTY: String = "dev.sebastiano.spectre.agent.runtimeJar"

private fun installEmbeddedAgentRuntimeIfNeeded() {
    if (System.getProperty(AGENT_RUNTIME_JAR_PROPERTY) != null) return
    EmbeddedAgentRuntime.install()?.let { runtime ->
        System.setProperty(AGENT_RUNTIME_JAR_PROPERTY, runtime.toString())
    }
}

private fun listJvmProcesses(
    discovery: DaemonJvmProcessDiscovery,
    requesterPid: Long,
): DaemonResponse = attachPreflightFailure() ?: discovery.list(requesterPid)

private fun attachPreflightFailure(): DaemonResponse.Error? =
    jdkPreflightError()?.let { message ->
        DaemonResponse.Error(code = DaemonErrorCode.AttachFailed, message = message)
    }

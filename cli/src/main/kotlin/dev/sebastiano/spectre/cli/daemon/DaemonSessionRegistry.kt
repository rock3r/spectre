package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAttachException
import dev.sebastiano.spectre.cli.hotreload.HotReloadCapability
import dev.sebastiano.spectre.cli.hotreload.HotReloadPortDiscovery
import dev.sebastiano.spectre.cli.hotreload.HotReloadSession
import dev.sebastiano.spectre.cli.hotreload.ReloadSettleErrorCategory
import dev.sebastiano.spectre.cli.hotreload.mapReloadSettleOutcome
import dev.sebastiano.spectre.cli.jdkPreflightError
import java.io.IOException

/** In-memory session table for one daemon process. */
@OptIn(ExperimentalSpectreAgentApi::class)
public class DaemonSessionRegistry
internal constructor(
    private val jvmProcessDiscovery: DaemonJvmProcessDiscovery = DaemonJvmProcessDiscovery(),
    private val hotReloadSessionFactory: (Long) -> HotReloadCapability? = { targetPid ->
        // Only become reload-aware when HR is detectable at attach time. Absent → no behavior
        // change for the session (#211 layering rule).
        if (HotReloadPortDiscovery.discover(targetPid) == null) {
            null
        } else {
            HotReloadSession.forTargetPid(targetPid)
        }
    },
    // Last so trailing-lambda call sites keep meaning attachAutomator.
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

    /**
     * Dispatches a daemon request. Long waits (#201) run **outside** the registry monitor so a
     * multi-second `waitForNode` does not freeze attach/ps for other clients.
     */
    public fun handle(request: DaemonRequest): DaemonResponse =
        when (request) {
            is DaemonRequest.WaitForNode,
            is DaemonRequest.WaitForVisualIdle,
            is DaemonRequest.WaitForReloadSettled -> handleWaitOutsideLock(request)
            else -> handleSynchronized(request)
        }

    @Synchronized
    private fun handleSynchronized(request: DaemonRequest): DaemonResponse =
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
            is DaemonRequest.FindByText,
            is DaemonRequest.FindByContentDescription,
            is DaemonRequest.FindByRole,
            is DaemonRequest.Click,
            is DaemonRequest.DoubleClick,
            is DaemonRequest.LongClick,
            is DaemonRequest.Swipe,
            is DaemonRequest.ScrollWheel,
            is DaemonRequest.PressKey,
            is DaemonRequest.TypeText,
            is DaemonRequest.Screenshot,
            is DaemonRequest.Capture,
            is DaemonRequest.StartRecording,
            is DaemonRequest.StopRecording,
            is DaemonRequest.RecordingStatus -> handleSessionCommand(request)
            // Wait ops are dispatched by [handle] outside the monitor.
            is DaemonRequest.WaitForNode,
            is DaemonRequest.WaitForVisualIdle,
            is DaemonRequest.WaitForReloadSettled ->
                error("wait ops must use handleWaitOutsideLock")
            DaemonRequest.Shutdown -> shutdown()
        }

    private fun handleWaitOutsideLock(request: DaemonRequest): DaemonResponse {
        val sessionId =
            when (request) {
                is DaemonRequest.WaitForNode -> request.sessionId
                is DaemonRequest.WaitForVisualIdle -> request.sessionId
                is DaemonRequest.WaitForReloadSettled -> request.sessionId
                else -> error("not a wait request")
            }
        // Pin the session and bump activeWaits under the monitor so detach/close will wait
        // for in-flight waits before closing the automator.
        val session =
            synchronized(this) {
                if (shutdown) {
                    return DaemonResponse.Error(
                        code = DaemonErrorCode.ShutdownInProgress,
                        message = "daemon is shutting down",
                    )
                }
                val found =
                    sessionsByPid.values.firstOrNull { it.sessionId == sessionId }
                        ?: return sessionNotFound(sessionId)
                found.activeWaits.incrementAndGet()
                found
            }
        return try {
            when (request) {
                is DaemonRequest.WaitForNode ->
                    DaemonResponse.Nodes(
                        request.sessionId,
                        listOf(
                            session.automator.waitForNode(
                                tag = request.tag,
                                text = request.text,
                                timeoutMs = request.timeoutMs,
                                pollIntervalMs = request.pollIntervalMs,
                            )
                        ),
                    )
                is DaemonRequest.WaitForVisualIdle -> {
                    session.automator.waitForVisualIdle(
                        timeoutMs = request.timeoutMs,
                        stableFrames = request.stableFrames,
                        pollIntervalMs = request.pollIntervalMs,
                    )
                    DaemonResponse.Completed(request.sessionId)
                }
                is DaemonRequest.WaitForReloadSettled -> {
                    val hotReload = session.hotReloadSession
                    if (hotReload == null) {
                        DaemonResponse.Error(
                            code = DaemonErrorCode.HotReloadUnavailable,
                            message = "hot reload is not available for this session",
                            category = ReloadSettleErrorCategory.HOT_RELOAD_UNAVAILABLE,
                        )
                    } else {
                        mapReloadSettleOutcome(
                            hotReload.waitForReloadSettled(request.timeoutMs),
                            request.sessionId,
                            request.timeoutMs,
                        )
                    }
                }
            }
        } catch (exception: IOException) {
            DaemonResponse.Error(
                code = DaemonErrorCode.OperationFailed,
                message = exception.message ?: "session operation failed",
            )
        } finally {
            session.activeWaits.decrementAndGet()
        }
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
            is DaemonRequest.FindByTestTag,
            is DaemonRequest.FindByText,
            is DaemonRequest.FindByContentDescription,
            is DaemonRequest.FindByRole -> handleQuerySessionCommand(request)
            is DaemonRequest.Click,
            is DaemonRequest.DoubleClick,
            is DaemonRequest.LongClick,
            is DaemonRequest.Swipe,
            is DaemonRequest.ScrollWheel,
            is DaemonRequest.PressKey,
            is DaemonRequest.TypeText -> handleInputSessionCommand(request)
            is DaemonRequest.Screenshot ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Screenshot(
                        request.sessionId,
                        automator.screenshot(
                            windowIndex = request.windowIndex,
                            surfaceId = request.surfaceId,
                            fullscreen = request.fullscreen,
                        ),
                    )
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
                        automator.startRecording(
                            request.outputPath,
                            request.windowIndex,
                            request.fullscreen,
                        ),
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
        val hotReload =
            try {
                hotReloadSessionFactory(targetPid)
            } catch (_: Exception) {
                null
            }
        val session =
            DaemonSession(
                summary = sessionSummaryFor(targetPid),
                automator = attached,
                hotReloadSession = hotReload,
            )
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
        val remainingLive = sessionsByPid.values.map { it.sessionId }.toSet() - sessionId
        val session = sessionsByPid.remove(removed.key)
        // Let in-flight waits finish before tearing down the automator (see handleWaitOutsideLock).
        session?.awaitIdleWaits()
        // Finalize recording while other sessions are still known live (#185 review).
        session?.automator?.finalizeRecording(remainingLive)
        session?.hotReloadSession?.close()
        session?.automator?.close()
        return CaptureSessionReport.forDetach(sessionId)
    }

    private fun liveSessionIds(): Set<String> = sessionsByPid.values.map { it.sessionId }.toSet()

    private fun handleQuerySessionCommand(request: DaemonRequest): DaemonResponse =
        when (request) {
            is DaemonRequest.FindByTestTag ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Nodes(request.sessionId, automator.findByTestTag(request.tag))
                }
            is DaemonRequest.FindByText ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Nodes(
                        request.sessionId,
                        automator.findByText(request.text, request.exact),
                    )
                }
            is DaemonRequest.FindByContentDescription ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Nodes(
                        request.sessionId,
                        automator.findByContentDescription(request.description),
                    )
                }
            is DaemonRequest.FindByRole ->
                invoke(request.sessionId) { automator ->
                    DaemonResponse.Nodes(request.sessionId, automator.findByRole(request.role))
                }
            else -> error("Not a query session command: ${request::class.simpleName}")
        }

    private fun handleInputSessionCommand(request: DaemonRequest): DaemonResponse =
        when (request) {
            is DaemonRequest.Click ->
                invoke(request.sessionId) { automator ->
                    automator.click(request.nodeKey)
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.DoubleClick ->
                invoke(request.sessionId) { automator ->
                    automator.doubleClick(request.nodeKey)
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.LongClick ->
                invoke(request.sessionId) { automator ->
                    automator.longClick(request.nodeKey, request.holdForMs)
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.Swipe ->
                invoke(request.sessionId) { automator ->
                    automator.swipe(
                        fromNodeKey = request.fromNodeKey,
                        toNodeKey = request.toNodeKey,
                        startX = request.startX,
                        startY = request.startY,
                        endX = request.endX,
                        endY = request.endY,
                        steps = request.steps,
                        durationMs = request.durationMs,
                    )
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.ScrollWheel ->
                invoke(request.sessionId) { automator ->
                    automator.scrollWheel(request.nodeKey, request.wheelClicks)
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.PressKey ->
                invoke(request.sessionId) { automator ->
                    automator.pressKey(request.keyCode, request.modifiers)
                    DaemonResponse.Completed(request.sessionId)
                }
            is DaemonRequest.TypeText ->
                invoke(request.sessionId) { automator ->
                    automator.typeText(request.text)
                    DaemonResponse.Completed(request.sessionId)
                }
            else -> error("Not an input session command: ${request::class.simpleName}")
        }

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

    private fun shutdown(): DaemonResponse {
        close()
        return DaemonResponse.ShuttingDown
    }

    @Synchronized
    override fun close() {
        shutdown = true
        val snapshot = sessionsByPid.values.toList()
        sessionsByPid.clear()
        // Outside the monitor after clear so wait finally-blocks can finish.
        snapshot.forEach { session ->
            session.awaitIdleWaits()
            session.hotReloadSession?.close()
            session.automator.close()
        }
    }

    private data class DaemonSession(
        val summary: DaemonSessionSummary,
        val automator: DaemonSessionAutomator,
        val hotReloadSession: HotReloadCapability? = null,
        val activeWaits: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger(0),
    ) {
        val sessionId: String
            get() = summary.sessionId

        fun awaitIdleWaits(timeoutMs: Long = WAIT_DRAIN_TIMEOUT_MS) {
            val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MS
            while (activeWaits.get() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(WAIT_DRAIN_POLL_MS)
            }
        }
    }

    private companion object {
        /**
         * Must cover long outside-lock waits such as [DaemonRequest.WaitForReloadSettled] (default
         * 60s) so detach/close does not tear down mid-wait. Kept above
         * [dev.sebastiano.spectre.cli.hotreload.HotReloadSession.DEFAULT_SETTLE_TIMEOUT_MS].
         */
        const val WAIT_DRAIN_TIMEOUT_MS: Long = 90_000
        const val WAIT_DRAIN_POLL_MS: Long = 10
        const val NANOS_PER_MS: Long = 1_000_000
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

private fun sessionNotFound(sessionId: String): DaemonResponse.Error =
    DaemonResponse.Error(
        code = DaemonErrorCode.SessionNotFound,
        message = "session not found: $sessionId",
    )

private fun sessionSummaryFor(targetPid: Long): DaemonSessionSummary =
    DaemonSessionSummary(sessionId = "pid-$targetPid", targetPid = targetPid)

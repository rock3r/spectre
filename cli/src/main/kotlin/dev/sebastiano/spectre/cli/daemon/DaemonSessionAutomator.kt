package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.io.IOException
import java.time.Clock

/** Operation surface retained by one daemon session. */
@OptIn(ExperimentalSpectreAgentApi::class)
internal interface DaemonSessionAutomator : AutoCloseable {
    @Throws(IOException::class) fun windows(): List<WindowSummaryDto>

    @Throws(IOException::class) fun allNodes(): List<NodeSnapshotDto>

    @Throws(IOException::class) fun findByTestTag(tag: String): List<NodeSnapshotDto>

    @Throws(IOException::class) fun click(nodeKey: String)

    @Throws(IOException::class) fun typeText(text: String)

    @Throws(IOException::class) fun screenshot(): ByteArray

    @Throws(IOException::class) fun capture(windowIndex: Int = 0): AtomicCaptureResult

    /**
     * Start daemon-owned window recording for this attach session.
     *
     * @param outputPath absolute path to the .mp4, or null to allocate under the capture root
     *   (`NNNN-timestamp/recording.mp4`) and ledger the directory (#181 / #185).
     */
    @Throws(IOException::class)
    fun startRecording(outputPath: String?, windowIndex: Int = 0): String

    @Throws(IOException::class) fun stopRecording(liveSessionIds: Set<String>): String

    /** Active recording path, or null when idle. */
    fun recordingStatus(): RecordingStatus
}

@OptIn(ExperimentalSpectreAgentApi::class)
internal class AttachedDaemonSession(
    private val delegate: AttachedAutomator,
    sessionId: String,
    clock: Clock = Clock.systemUTC(),
    ledger: CaptureLedger = CaptureLifecycle.ledger(),
) : DaemonSessionAutomator {
    private val recording =
        DaemonSessionRecording(
            delegate = delegate,
            sessionId = sessionId,
            clock = clock,
            ledger = ledger,
        )

    override fun windows(): List<WindowSummaryDto> = delegate.windows()

    override fun allNodes(): List<NodeSnapshotDto> = delegate.allNodes()

    override fun findByTestTag(tag: String): List<NodeSnapshotDto> = delegate.findByTestTag(tag)

    override fun click(nodeKey: String): Unit = delegate.click(nodeKey)

    override fun typeText(text: String): Unit = delegate.typeText(text)

    override fun screenshot(): ByteArray = delegate.screenshot()

    override fun capture(windowIndex: Int): AtomicCaptureResult = delegate.capture(windowIndex)

    override fun startRecording(outputPath: String?, windowIndex: Int): String =
        recording.start(outputPath, windowIndex)

    override fun stopRecording(liveSessionIds: Set<String>): String = recording.stop(liveSessionIds)

    override fun recordingStatus(): RecordingStatus = recording.status()

    override fun close() {
        try {
            // Finalize recording on detach even if the target JVM is already dead (#185).
            // liveSessionIds is empty here because this session is leaving the table.
            recording.finalizeIfActive(liveSessionIds = emptySet())
        } finally {
            delegate.close()
        }
    }
}

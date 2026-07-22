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
@Suppress("TooManyFunctions") // Session surface grows with transport parity (#203).
internal interface DaemonSessionAutomator : AutoCloseable {
    @Throws(IOException::class) fun windows(): List<WindowSummaryDto>

    @Throws(IOException::class) fun allNodes(): List<NodeSnapshotDto>

    @Throws(IOException::class) fun findByTestTag(tag: String): List<NodeSnapshotDto>

    @Throws(IOException::class) fun click(nodeKey: String)

    @Throws(IOException::class) fun doubleClick(nodeKey: String)

    @Throws(IOException::class) fun longClick(nodeKey: String, holdForMs: Long = 500)

    @Throws(IOException::class)
    fun swipe(
        fromNodeKey: String? = null,
        toNodeKey: String? = null,
        startX: Int? = null,
        startY: Int? = null,
        endX: Int? = null,
        endY: Int? = null,
        steps: Int = 12,
        durationMs: Long = 200,
    )

    @Throws(IOException::class) fun scrollWheel(nodeKey: String, wheelClicks: Int)

    @Throws(IOException::class) fun pressKey(keyCode: Int, modifiers: Int = 0)

    @Throws(IOException::class) fun typeText(text: String)

    @Throws(IOException::class)
    fun screenshot(
        windowIndex: Int? = null,
        surfaceId: String? = null,
        fullscreen: Boolean = false,
    ): ByteArray

    @Throws(IOException::class) fun capture(windowIndex: Int = 0): AtomicCaptureResult

    /**
     * Start daemon-owned recording for this attach session.
     *
     * @param outputPath absolute path to the .mp4, or null to allocate under the capture root
     *   (`NNNN-timestamp/recording.mp4`) and ledger the directory (#181 / #185).
     * @param windowIndex tracked non-popup window when [fullscreen] is false.
     * @param fullscreen when true, record the full virtual desktop (region capture) instead of a
     *   window.
     */
    @Throws(IOException::class)
    fun startRecording(
        outputPath: String?,
        windowIndex: Int = 0,
        fullscreen: Boolean = false,
    ): String

    @Throws(IOException::class) fun stopRecording(liveSessionIds: Set<String>): String

    /** Active recording path, or null when idle. */
    fun recordingStatus(): RecordingStatus

    /**
     * Finalize any active recording before the session is dropped. [remainingLiveSessionIds] must
     * be every other still-attached session so retention does not delete their capture dirs.
     */
    fun finalizeRecording(remainingLiveSessionIds: Set<String>)
}

@OptIn(ExperimentalSpectreAgentApi::class)
@Suppress("TooManyFunctions") // Implements DaemonSessionAutomator 1:1.
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

    override fun doubleClick(nodeKey: String): Unit = delegate.doubleClick(nodeKey)

    override fun longClick(nodeKey: String, holdForMs: Long): Unit =
        delegate.longClick(nodeKey, holdForMs)

    override fun swipe(
        fromNodeKey: String?,
        toNodeKey: String?,
        startX: Int?,
        startY: Int?,
        endX: Int?,
        endY: Int?,
        steps: Int,
        durationMs: Long,
    ) {
        if (fromNodeKey != null && toNodeKey != null) {
            delegate.swipe(fromNodeKey, toNodeKey, steps, durationMs)
            return
        }
        if (startX != null && startY != null && endX != null && endY != null) {
            delegate.swipe(startX, startY, endX, endY, steps, durationMs)
            return
        }
        throw IOException(
            "swipe requires fromNodeKey+toNodeKey or startX/startY/endX/endY coordinates"
        )
    }

    override fun scrollWheel(nodeKey: String, wheelClicks: Int): Unit =
        delegate.scrollWheel(nodeKey, wheelClicks)

    override fun pressKey(keyCode: Int, modifiers: Int): Unit =
        delegate.pressKey(keyCode, modifiers)

    override fun typeText(text: String): Unit = delegate.typeText(text)

    override fun screenshot(windowIndex: Int?, surfaceId: String?, fullscreen: Boolean): ByteArray =
        delegate.screenshot(
            windowIndex = windowIndex,
            surfaceId = surfaceId,
            fullscreen = fullscreen,
        )

    override fun capture(windowIndex: Int): AtomicCaptureResult = delegate.capture(windowIndex)

    override fun startRecording(
        outputPath: String?,
        windowIndex: Int,
        fullscreen: Boolean,
    ): String = recording.start(outputPath, windowIndex, fullscreen)

    override fun stopRecording(liveSessionIds: Set<String>): String = recording.stop(liveSessionIds)

    override fun recordingStatus(): RecordingStatus = recording.status()

    override fun finalizeRecording(remainingLiveSessionIds: Set<String>) {
        recording.finalizeIfActive(remainingLiveSessionIds)
    }

    override fun close() {
        try {
            // Prefer finalizeRecording from the registry with remaining live IDs. Fallback if
            // close is called without that (e.g. daemon shutdown of last session).
            recording.finalizeIfActive(liveSessionIds = emptySet())
        } finally {
            delegate.close()
        }
    }
}

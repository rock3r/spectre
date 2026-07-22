package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto

@OptIn(ExperimentalSpectreAgentApi::class)
internal class TestDaemonSessionAutomator(
    private val windowsResult: () -> List<WindowSummaryDto> = { emptyList() },
    private val nodesResult: () -> List<NodeSnapshotDto> = { emptyList() },
    private val findByTestTagResult: (String) -> List<NodeSnapshotDto> = { emptyList() },
    private val clickAction: (String) -> Unit = {},
    private val typeTextAction: (String) -> Unit = {},
    private val screenshotResult:
        (windowIndex: Int?, surfaceId: String?, fullscreen: Boolean) -> ByteArray =
        { _, _, _ ->
            ByteArray(0)
        },
    private val captureResult: (Int) -> AtomicCaptureResult = { windowIndex ->
        AtomicCaptureResult(
            windowIndex = windowIndex,
            schemaVersion = 1,
            captureJson = """{"schemaVersion":1}""",
            pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            nodeCount = 0,
            taggedNodeCount = 0,
            textedNodeCount = 0,
            imageWidth = 1,
            imageHeight = 1,
            captureDurationMs = 0,
        )
    },
    private val startRecordingAction: (String?, Int, Boolean) -> String = { path, _, _ ->
        path ?: "/tmp/spectre-recording.mp4"
    },
    private val stopRecordingResult: (Set<String>) -> String = {
        error("no recording is in progress")
    },
    private val recordingStatusResult: () -> RecordingStatus = { RecordingStatus(active = false) },
    private val closeAction: () -> Unit = {},
) : DaemonSessionAutomator {
    override fun windows(): List<WindowSummaryDto> = windowsResult()

    override fun allNodes(): List<NodeSnapshotDto> = nodesResult()

    override fun findByTestTag(tag: String): List<NodeSnapshotDto> = findByTestTagResult(tag)

    override fun click(nodeKey: String): Unit = clickAction(nodeKey)

    override fun doubleClick(nodeKey: String): Unit = Unit

    override fun longClick(nodeKey: String, holdForMs: Long): Unit = Unit

    override fun swipe(
        fromNodeKey: String?,
        toNodeKey: String?,
        startX: Int?,
        startY: Int?,
        endX: Int?,
        endY: Int?,
        steps: Int,
        durationMs: Long,
    ): Unit = Unit

    override fun scrollWheel(nodeKey: String, wheelClicks: Int): Unit = Unit

    override fun pressKey(keyCode: Int, modifiers: Int): Unit = Unit

    override fun typeText(text: String): Unit = typeTextAction(text)

    override fun screenshot(windowIndex: Int?, surfaceId: String?, fullscreen: Boolean): ByteArray =
        screenshotResult(windowIndex, surfaceId, fullscreen)

    override fun capture(windowIndex: Int): AtomicCaptureResult = captureResult(windowIndex)

    override fun startRecording(
        outputPath: String?,
        windowIndex: Int,
        fullscreen: Boolean,
    ): String = startRecordingAction(outputPath, windowIndex, fullscreen)

    override fun stopRecording(liveSessionIds: Set<String>): String =
        stopRecordingResult(liveSessionIds)

    override fun recordingStatus(): RecordingStatus = recordingStatusResult()

    override fun finalizeRecording(remainingLiveSessionIds: Set<String>): Unit = Unit

    override fun close(): Unit = closeAction()
}

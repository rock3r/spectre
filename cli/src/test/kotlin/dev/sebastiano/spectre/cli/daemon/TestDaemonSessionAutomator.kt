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
    private val screenshotResult: () -> ByteArray = { ByteArray(0) },
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
    private val startRecordingAction: (String?, Int) -> String = { path, _ ->
        path ?: "/tmp/spectre-recording.mp4"
    },
    private val stopRecordingResult: () -> String = { error("no recording is in progress") },
    private val recordingStatusResult: () -> RecordingStatus = { RecordingStatus(active = false) },
    private val closeAction: () -> Unit = {},
) : DaemonSessionAutomator {
    override fun windows(): List<WindowSummaryDto> = windowsResult()

    override fun allNodes(): List<NodeSnapshotDto> = nodesResult()

    override fun findByTestTag(tag: String): List<NodeSnapshotDto> = findByTestTagResult(tag)

    override fun click(nodeKey: String): Unit = clickAction(nodeKey)

    override fun typeText(text: String): Unit = typeTextAction(text)

    override fun screenshot(): ByteArray = screenshotResult()

    override fun capture(windowIndex: Int): AtomicCaptureResult = captureResult(windowIndex)

    override fun startRecording(outputPath: String?, windowIndex: Int): String =
        startRecordingAction(outputPath, windowIndex)

    override fun stopRecording(): String = stopRecordingResult()

    override fun recordingStatus(): RecordingStatus = recordingStatusResult()

    override fun close(): Unit = closeAction()
}

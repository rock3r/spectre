package dev.sebastiano.spectre.cli.daemon

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
    private val closeAction: () -> Unit = {},
) : DaemonSessionAutomator {
    override fun windows(): List<WindowSummaryDto> = windowsResult()

    override fun allNodes(): List<NodeSnapshotDto> = nodesResult()

    override fun findByTestTag(tag: String): List<NodeSnapshotDto> = findByTestTagResult(tag)

    override fun click(nodeKey: String): Unit = clickAction(nodeKey)

    override fun typeText(text: String): Unit = typeTextAction(text)

    override fun screenshot(): ByteArray = screenshotResult()

    override fun close(): Unit = closeAction()
}

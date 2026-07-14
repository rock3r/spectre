package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.io.IOException

/** Operation surface retained by one daemon session. */
@OptIn(ExperimentalSpectreAgentApi::class)
internal interface DaemonSessionAutomator : AutoCloseable {
    @Throws(IOException::class) fun windows(): List<WindowSummaryDto>

    @Throws(IOException::class) fun allNodes(): List<NodeSnapshotDto>

    @Throws(IOException::class) fun findByTestTag(tag: String): List<NodeSnapshotDto>

    @Throws(IOException::class) fun click(nodeKey: String)

    @Throws(IOException::class) fun typeText(text: String)

    @Throws(IOException::class) fun screenshot(): ByteArray
}

@OptIn(ExperimentalSpectreAgentApi::class)
internal class AttachedDaemonSession(private val delegate: AttachedAutomator) :
    DaemonSessionAutomator {
    override fun windows(): List<WindowSummaryDto> = delegate.windows()

    override fun allNodes(): List<NodeSnapshotDto> = delegate.allNodes()

    override fun findByTestTag(tag: String): List<NodeSnapshotDto> = delegate.findByTestTag(tag)

    override fun click(nodeKey: String): Unit = delegate.click(nodeKey)

    override fun typeText(text: String): Unit = delegate.typeText(text)

    override fun screenshot(): ByteArray = delegate.screenshot()

    override fun close(): Unit = delegate.close()
}

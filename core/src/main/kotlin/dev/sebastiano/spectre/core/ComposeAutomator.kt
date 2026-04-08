package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ComposeAutomator
private constructor(
    private val windowTracker: WindowTracker,
    private val semanticsReader: SemanticsReader,
    private val robotDriver: RobotDriver,
) {

    val windows: List<TrackedWindow>
        get() = windowTracker.trackedWindows

    fun refreshWindows() {
        windowTracker.refresh()
    }

    fun allNodes(): List<AutomatorNode> = semanticsReader.readAllNodes(windows)

    fun findByTestTag(tag: String): List<AutomatorNode> =
        semanticsReader.findByTestTag(tag, windows)

    fun findOneByTestTag(tag: String): AutomatorNode? = findByTestTag(tag).firstOrNull()

    fun findByText(text: String, exact: Boolean = true): List<AutomatorNode> =
        semanticsReader.findByText(text, windows, exact)

    fun findOneByText(text: String, exact: Boolean = true): AutomatorNode? =
        findByText(text, exact).firstOrNull()

    fun findByContentDescription(description: String): List<AutomatorNode> =
        semanticsReader.findByContentDescription(description, windows)

    fun findByRole(role: Role): List<AutomatorNode> = semanticsReader.findByRole(role, windows)

    fun click(node: AutomatorNode) {
        val center = node.centerOnScreen
        robotDriver.click(center.x, center.y)
    }

    fun doubleClick(node: AutomatorNode) {
        val center = node.centerOnScreen
        robotDriver.doubleClick(center.x, center.y)
    }

    fun typeText(text: String) {
        robotDriver.typeText(text)
    }

    fun pressKey(keyCode: Int, modifiers: Int = 0) {
        robotDriver.pressKey(keyCode, modifiers)
    }

    fun screenshot(region: Rectangle? = null): BufferedImage = robotDriver.screenshot(region)

    suspend fun waitForNode(
        tag: String? = null,
        text: String? = null,
        timeout: Duration = 5.seconds,
        pollInterval: Duration = 100.milliseconds,
    ): AutomatorNode {
        require(tag != null || text != null) { "Either tag or text must be specified" }
        return waitUntil(timeout = timeout, pollInterval = pollInterval) {
            refreshWindows()
            val byTag = if (tag != null) findByTestTag(tag) else allNodes()
            if (text == null) {
                byTag.firstOrNull()
            } else {
                val textKeys = findByText(text).map { it.key }.toSet()
                byTag.firstOrNull { it.key in textKeys }
            }
        }
    }

    fun printTree(): String = buildString {
        refreshWindows()
        for ((windowIndex, trackedWindow) in windows.withIndex()) {
            val kind = if (trackedWindow.isPopup) "popup" else "main"
            appendLine("Window $windowIndex ($kind): ${trackedWindow.surfaceId}")
            val allNodes = semanticsReader.readAllNodes(listOf(trackedWindow))
            val roots = allNodes.filter { it.semanticsNode.parent == null }
            for (root in roots) {
                appendNodeTree(root, allNodes, depth = 1)
            }
        }
    }

    companion object {

        fun create(
            windowTracker: WindowTracker = WindowTracker(),
            semanticsReader: SemanticsReader = SemanticsReader(),
            robotDriver: RobotDriver = RobotDriver(),
        ): ComposeAutomator = ComposeAutomator(windowTracker, semanticsReader, robotDriver)
    }
}

private fun StringBuilder.appendNodeTree(
    node: AutomatorNode,
    allNodes: List<AutomatorNode>,
    depth: Int,
) {
    append("  ".repeat(depth))
    append("[${node.key.nodeId}]")
    node.testTag?.let { append(" testTag=\"$it\"") }
    node.text?.let { append(" text=\"$it\"") }
    node.role?.let { append(" role=$it") }
    if (node.isFocused) append(" focused")
    if (node.isDisabled) append(" disabled")
    appendLine()
    // Match children by full NodeKey (ownerIndex + nodeId) to avoid cross-owner collisions
    for (child in node.semanticsNode.children) {
        val childKey = NodeKey(node.key.surfaceId, node.key.ownerIndex, child.id)
        val childNode = allNodes.find { it.key == childKey }
        if (childNode != null) {
            appendNodeTree(childNode, allNodes, depth + 1)
        }
    }
}

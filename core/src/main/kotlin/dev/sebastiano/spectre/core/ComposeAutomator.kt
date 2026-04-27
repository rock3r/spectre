package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

sealed class ComposeAutomatorQueries {

    protected abstract val windowTracker: WindowTracker
    protected abstract val semanticsReader: SemanticsReader

    val windows: List<TrackedWindow>
        get() = windowTracker.trackedWindows

    fun refreshWindows() {
        windowTracker.refresh()
    }

    fun tree(): AutomatorTree {
        refreshWindows()
        val windowScopes = windows.mapIndexed { index, trackedWindow ->
            AutomatorWindow(
                windowIndex = index,
                trackedWindow = trackedWindow,
                nodes = semanticsReader.readAllNodes(listOf(trackedWindow)),
            )
        }
        return AutomatorTree(windowScopes)
    }

    fun tree(windowIndex: Int): AutomatorWindow = tree().window(windowIndex)

    fun allNodes(): List<AutomatorNode> = semanticsReader.readAllNodes(windows)

    fun findByTestTag(tag: String): List<AutomatorNode> =
        semanticsReader.findByTestTag(tag, windows)

    fun findOneByTestTag(tag: String): AutomatorNode? = findByTestTag(tag).firstOrNull()

    fun findByText(query: TextQuery): List<AutomatorNode> =
        semanticsReader.findByText(query, windows)

    fun findByText(text: String, exact: Boolean = true): List<AutomatorNode> =
        semanticsReader.findByText(text, windows, exact)

    fun findOneByText(query: TextQuery): AutomatorNode? = findByText(query).firstOrNull()

    fun findOneByText(text: String, exact: Boolean = true): AutomatorNode? =
        findByText(text, exact).firstOrNull()

    fun findByContentDescription(description: String): List<AutomatorNode> =
        semanticsReader.findByContentDescription(description, windows)

    fun findByRole(role: Role): List<AutomatorNode> = semanticsReader.findByRole(role, windows)
}

sealed class ComposeAutomatorInteractions : ComposeAutomatorQueries() {

    protected abstract val robotDriver: RobotDriver

    fun click(node: AutomatorNode) {
        val center = node.centerOnScreen
        robotDriver.click(center.x, center.y)
    }

    fun doubleClick(node: AutomatorNode) {
        val center = node.centerOnScreen
        robotDriver.doubleClick(center.x, center.y)
    }

    fun longClick(node: AutomatorNode, holdFor: Duration = 500.milliseconds) {
        val center = node.centerOnScreen
        robotDriver.longClick(center.x, center.y, holdFor)
    }

    fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        steps: Int = 12,
        duration: Duration = 200.milliseconds,
    ) {
        robotDriver.swipe(startX, startY, endX, endY, steps, duration)
    }

    fun swipe(
        from: AutomatorNode,
        to: AutomatorNode,
        steps: Int = 12,
        duration: Duration = 200.milliseconds,
    ) {
        val fromCenter = from.centerOnScreen
        val toCenter = to.centerOnScreen
        swipe(fromCenter.x, fromCenter.y, toCenter.x, toCenter.y, steps, duration)
    }

    fun typeText(text: String) {
        robotDriver.typeText(text)
    }

    fun clearAndTypeText(node: AutomatorNode, text: String) {
        click(node)
        robotDriver.clearAndTypeText(text)
    }

    fun pressKey(keyCode: Int, modifiers: Int = 0) {
        robotDriver.pressKey(keyCode, modifiers)
    }

    fun pressEnter() {
        robotDriver.pressKey(KeyEvent.VK_ENTER)
    }

    fun screenshot(region: Rectangle? = null): BufferedImage = robotDriver.screenshot(region)

    fun screenshot(node: AutomatorNode): BufferedImage = robotDriver.screenshot(node.boundsOnScreen)

    fun screenshot(windowIndex: Int): BufferedImage {
        refreshWindows()
        val trackedWindow =
            windows.getOrNull(windowIndex)
                ?: error("No tracked window at index $windowIndex (have ${windows.size})")
        return robotDriver.screenshot(trackedWindow.composeSurfaceBoundsOnScreen)
    }
}

class ComposeAutomator
private constructor(
    override val windowTracker: WindowTracker,
    override val semanticsReader: SemanticsReader,
    override val robotDriver: RobotDriver,
) : ComposeAutomatorInteractions() {

    suspend fun waitForNode(
        tag: String? = null,
        text: String? = null,
        timeout: Duration = 5.seconds,
        pollInterval: Duration = 100.milliseconds,
    ): AutomatorNode {
        require(tag != null || text != null) { "Either tag or text must be specified" }
        return waitUntil(timeout = timeout, pollInterval = pollInterval) {
            readOnEdt {
                refreshWindows()
                allNodes().firstOrNull { node ->
                    (tag == null || node.testTag == tag) &&
                        (text == null || node.texts.any { it == text } || node.editableText == text)
                }
            }
        }
    }

    fun printTree(): String {
        return readOnEdt {
            buildString {
                // tree() already refreshes windows before reading semantics nodes.
                for (window in tree().windows()) {
                    val kind = if (window.isPopup) "popup" else "main"
                    appendLine("Window ${window.windowIndex} ($kind): ${window.surfaceId}")
                    for (root in window.roots()) {
                        appendNodeTree(root, depth = 1)
                    }
                }
            }
        }
    }

    companion object {

        fun inProcess(
            windowTracker: WindowTracker = WindowTracker(),
            semanticsReader: SemanticsReader = SemanticsReader(),
            robotDriver: RobotDriver = RobotDriver(),
        ): ComposeAutomator = ComposeAutomator(windowTracker, semanticsReader, robotDriver)
    }
}

private fun StringBuilder.appendNodeTree(node: AutomatorNode, depth: Int) {
    append("  ".repeat(depth))
    append("[${node.key.nodeId}]")
    node.testTag?.let { append(" testTag=\"$it\"") }
    node.text?.let { append(" text=\"$it\"") }
    node.role?.let { append(" role=$it") }
    if (node.isFocused) append(" focused")
    if (node.isDisabled) append(" disabled")
    appendLine()
    for (child in node.children) {
        appendNodeTree(child, depth + 1)
    }
}

package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
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

    // V1 contract: queries and actions do not auto-wait. Callers must invoke waitForIdle() /
    // waitForVisualIdle() / waitForNode() explicitly when synchronisation matters. Auto-wait
    // wrapping every read/action is intentionally deferred — see the v1 issue tracker.
    private val idlingResources = CopyOnWriteArrayList<AutomatorIdlingResource>()

    fun registerIdlingResource(resource: AutomatorIdlingResource) {
        idlingResources.addIfAbsent(resource)
    }

    fun unregisterIdlingResource(resource: AutomatorIdlingResource) {
        idlingResources.remove(resource)
    }

    suspend fun waitForIdle(
        timeout: Duration = DEFAULT_WAIT_TIMEOUT,
        quietPeriod: Duration = DEFAULT_QUIET_PERIOD,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ) {
        waitForIdleInternal(
            timeout = timeout,
            quietPeriod = quietPeriod,
            pollInterval = pollInterval,
            idlingResources = { idlingResources.toList() },
            drainEdt = ::drainEdt,
            fingerprint = ::computeUiFingerprint,
        )
    }

    suspend fun waitForVisualIdle(
        timeout: Duration = DEFAULT_WAIT_TIMEOUT,
        stableFrames: Int = DEFAULT_STABLE_FRAMES,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ) {
        waitForVisualIdleInternal(
            timeout = timeout,
            stableFrames = stableFrames,
            pollInterval = pollInterval,
            frameHash = ::computeFrameHash,
        )
    }

    private fun drainEdt() {
        if (SwingUtilities.isEventDispatchThread()) return
        SwingUtilities.invokeAndWait {}
    }

    private fun computeUiFingerprint(): String = readOnEdt {
        refreshWindows()
        buildString {
            for (window in windows) {
                append(window.surfaceId)
                append('|')
                val nodes = semanticsReader.readAllNodes(listOf(window))
                append(nodes.size)
                append('|')
                for (node in nodes) {
                    append(node.key.toString())
                    val bounds = node.boundsInWindow
                    append('@')
                    append(bounds.left.toBits())
                    append(',')
                    append(bounds.top.toBits())
                    append(',')
                    append(bounds.right.toBits())
                    append(',')
                    append(bounds.bottom.toBits())
                    node.role?.let {
                        append(':')
                        append(it.toString())
                    }
                    if (node.isFocused) append(":F")
                    if (node.isDisabled) append(":D")
                    append(';')
                }
                append("||")
            }
        }
    }

    private fun computeFrameHash(): Int {
        val image = robotDriver.screenshot()
        val raster = image.raster
        val buffer = raster.dataBuffer
        return if (buffer is DataBufferInt) {
            buffer.data.contentHashCode()
        } else {
            // Fallback: read pixels generically. Slower, but correct for any BufferedImage type.
            val width = image.width
            val height = image.height
            val pixels = image.getRGB(0, 0, width, height, null, 0, width)
            pixels.contentHashCode()
        }
    }

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

private val DEFAULT_WAIT_TIMEOUT: Duration = 5.seconds
private val DEFAULT_QUIET_PERIOD: Duration = 64.milliseconds
private val DEFAULT_POLL_INTERVAL: Duration = 16.milliseconds
private const val DEFAULT_STABLE_FRAMES: Int = 3

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

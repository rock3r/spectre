package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        // Bounded drain: invokeAndWait can hang indefinitely if the EDT is deadlocked, which
        // would let waitForIdle silently overrun its timeout. We dispatch via invokeLater and
        // wait on a latch capped at EDT_DRAIN_BUDGET_MS so a stalled EDT can never out-block
        // the surrounding wait loop's timeout enforcement.
        val latch = CountDownLatch(1)
        SwingUtilities.invokeLater { latch.countDown() }
        latch.await(EDT_DRAIN_BUDGET_MS, TimeUnit.MILLISECONDS)
    }

    private fun computeUiFingerprint(): String =
        runBoundedOnWorker(FINGERPRINT_BUDGET_MS) { computeUiFingerprintUnbounded() }
            ?: "${EMPTY_FINGERPRINT_PREFIX}${System.nanoTime()}"

    private fun computeUiFingerprintUnbounded(): String = readOnEdt {
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
                    if (node.isSelected) append(":S")
                    if (node.texts.isNotEmpty()) {
                        append(":T")
                        append(node.texts.joinToString(separator = "").hashCode())
                    }
                    if (node.contentDescriptions.isNotEmpty()) {
                        append(":C")
                        append(node.contentDescriptions.joinToString(separator = "").hashCode())
                    }
                    node.editableText?.let {
                        append(":E")
                        append(it.hashCode())
                    }
                    append(';')
                }
                append("||")
            }
        }
    }

    private fun computeFrameHash(): Int {
        // Hash each tracked Compose surface independently, then combine the per-surface hashes.
        // - Sampling the full virtual desktop would let unrelated pixel churn (notifications,
        //   other apps, the cursor outside the app) break the stable-frame streak.
        // - A single union rectangle would include the gap between disjoint surfaces (main
        //   window + a popup floating elsewhere), and gap-pixel churn would still break the
        //   streak. Per-surface hashes ignore the gap entirely.
        // - When no surfaces are tracked, or the bounded sampling budget elapses, we
        //   deliberately return a value that differs every call so the streak never completes
        //   — waitForVisualIdle keeps waiting (or times out) rather than declaring success
        //   against an unsampleable UI.
        // The sampling itself runs on a worker thread bounded by FRAME_HASH_BUDGET_MS so a
        // hung EDT or stuck Robot.createScreenCapture cannot out-block the wait loop's
        // overall timeout enforcement.
        return runBoundedOnWorker(FRAME_HASH_BUDGET_MS) {
            refreshWindows()
            val rects = composeSurfaceRects()
            if (rects.isEmpty()) {
                System.nanoTime().toInt()
            } else {
                val hashes = IntArray(rects.size)
                for (i in rects.indices) hashes[i] = hashScreenRegion(rects[i])
                hashes.contentHashCode()
            }
        } ?: System.nanoTime().toInt()
    }

    private fun <T> runBoundedOnWorker(budgetMs: Long, block: () -> T): T? {
        // We deliberately use a dedicated daemon Thread (not CompletableFuture.supplyAsync,
        // which runs on ForkJoinPool.commonPool) for two reasons:
        // 1. CompletableFuture.cancel ignores mayInterruptIfRunning and never interrupts the
        //    underlying worker, so timed-out tasks would leak threads onto the common pool.
        // 2. A daemon Thread can be Thread.interrupt()-ed, which lets blocking calls that do
        //    honour interrupts (notably SwingUtilities.invokeAndWait used by readOnEdt) bail
        //    out and free the thread up. Native calls like Robot.createScreenCapture do not
        //    honour interrupts, so a stuck Robot can still leak a single daemon thread, but
        //    daemon status keeps it from holding the JVM open.
        val resultRef = AtomicReference<Result<T>?>(null)
        val latch = CountDownLatch(1)
        val thread =
            Thread(
                    {
                        // The wrapped block runs untrusted user/Compose code (semantics
                        // reads, AWT calls, screenshot capture). We genuinely want every
                        // failure mode propagated back to the caller via the Result so the
                        // wait loop can decide what to do, hence the broad catch.
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            resultRef.set(Result.success(block()))
                        } catch (t: Throwable) {
                            resultRef.set(Result.failure(t))
                        } finally {
                            latch.countDown()
                        }
                    },
                    "spectre-bounded-worker",
                )
                .apply { isDaemon = true }
        thread.start()
        return if (latch.await(budgetMs, TimeUnit.MILLISECONDS)) {
            resultRef.get()!!.getOrThrow()
        } else {
            thread.interrupt()
            null
        }
    }

    private fun composeSurfaceRects(): List<Rectangle> = readOnEdt {
        windows.mapNotNull { window ->
            runCatching { window.composeSurfaceBoundsOnScreen }.getOrNull()?.takeIf { !it.isEmpty }
        }
    }

    private fun hashScreenRegion(region: Rectangle): Int {
        val image = robotDriver.screenshot(region)
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
private const val EDT_DRAIN_BUDGET_MS: Long = 250
private const val FRAME_HASH_BUDGET_MS: Long = 500
private const val FINGERPRINT_BUDGET_MS: Long = 500
private const val EMPTY_FINGERPRINT_PREFIX: String = "spectre-fingerprint-budget-elapsed:"

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

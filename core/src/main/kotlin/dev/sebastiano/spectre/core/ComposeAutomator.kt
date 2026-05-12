@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Single user-facing entry point for driving live Compose Desktop UIs: tracked-window discovery,
 * node lookup, input dispatch, screenshot capture, and synchronisation. The R1 design choice is
 * intentionally to keep this surface in one place — splitting it into extension-function files
 * would force private wait/fingerprint helpers to widen to `internal` and would force every caller
 * to add explicit per-symbol imports for `click` / `findByTestTag` / `screenshot` / etc., which is
 * friction without a corresponding win for discoverability.
 */
@Suppress("TooManyFunctions")
public class ComposeAutomator
private constructor(
    private val windowTracker: WindowTracker,
    private val semanticsReader: SemanticsReader,
    private val robotDriver: RobotDriver,
) {

    /**
     * Snapshot of the currently tracked windows. Returns the live `TrackedWindow` collaborator
     * type, which is part of Spectre's internal escape hatch — typical users should call
     * [surfaceIds] instead. The HTTP transport in `:server` is the one in-repo consumer that needs
     * the rich type.
     */
    @InternalSpectreApi
    public val windows: List<TrackedWindow>
        get() = windowTracker.trackedWindows

    /** Stable surface IDs of every tracked window, in tracking order. */
    public fun surfaceIds(): List<String> = windowTracker.trackedWindows.map { it.surfaceId }

    public fun refreshWindows() {
        windowTracker.refresh()
    }

    public fun tree(): AutomatorTree {
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

    public fun tree(windowIndex: Int): AutomatorWindow = tree().window(windowIndex)

    public fun allNodes(): List<AutomatorNode> = semanticsReader.readAllNodes(windows)

    public fun findByTestTag(tag: String): List<AutomatorNode> =
        semanticsReader.findByTestTag(tag, windows)

    public fun findOneByTestTag(tag: String): AutomatorNode? = findByTestTag(tag).firstOrNull()

    public fun findByText(query: TextQuery): List<AutomatorNode> =
        semanticsReader.findByText(query, windows)

    public fun findByText(text: String, exact: Boolean = true): List<AutomatorNode> =
        semanticsReader.findByText(text, windows, exact)

    public fun findOneByText(query: TextQuery): AutomatorNode? = findByText(query).firstOrNull()

    public fun findOneByText(text: String, exact: Boolean = true): AutomatorNode? =
        findByText(text, exact).firstOrNull()

    public fun findByContentDescription(description: String): List<AutomatorNode> =
        semanticsReader.findByContentDescription(description, windows)

    public fun findByRole(role: Role): List<AutomatorNode> =
        semanticsReader.findByRole(role, windows)

    public suspend fun click(node: AutomatorNode) {
        val center = node.centerOnScreen
        robotDriver.click(center.x, center.y)
    }

    public suspend fun doubleClick(node: AutomatorNode) {
        val center = node.centerOnScreen
        robotDriver.doubleClick(center.x, center.y)
    }

    public suspend fun longClick(node: AutomatorNode, holdFor: Duration = 500.milliseconds) {
        val center = node.centerOnScreen
        robotDriver.longClick(center.x, center.y, holdFor)
    }

    public suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        steps: Int = 12,
        duration: Duration = 200.milliseconds,
    ) {
        robotDriver.swipe(startX, startY, endX, endY, steps, duration)
    }

    public suspend fun swipe(
        from: AutomatorNode,
        to: AutomatorNode,
        steps: Int = 12,
        duration: Duration = 200.milliseconds,
    ) {
        val fromCenter = from.centerOnScreen
        val toCenter = to.centerOnScreen
        swipe(fromCenter.x, fromCenter.y, toCenter.x, toCenter.y, steps, duration)
    }

    /**
     * Scrolls vertically at [node]'s centre. Positive [wheelClicks] scrolls down (revealing items
     * lower in the list); negative scrolls up. Drives Compose's `Modifier.scrollable` /
     * `LazyColumn` on desktop, which respond to wheel events rather than touch-style drags.
     */
    public suspend fun scrollWheel(node: AutomatorNode, wheelClicks: Int) {
        val center = node.centerOnScreen
        robotDriver.scrollWheel(center.x, center.y, wheelClicks)
    }

    public suspend fun typeText(text: String) {
        robotDriver.typeText(text)
    }

    public suspend fun clearAndTypeText(node: AutomatorNode, text: String) {
        click(node)
        robotDriver.clearAndTypeText(text)
    }

    public suspend fun pressKey(keyCode: Int, modifiers: Int = 0) {
        robotDriver.pressKey(keyCode, modifiers)
    }

    public suspend fun pressEnter() {
        robotDriver.pressKey(KeyEvent.VK_ENTER)
    }

    /**
     * Raises and requests focus on the AWT window that hosts [node]. Useful before a sequence of
     * Robot-driven inputs on a non-focused window. The actual focus change is dispatched on the
     * EDT.
     */
    public fun focusWindow(node: AutomatorNode) {
        val window = node.trackedWindow.window
        if (SwingUtilities.isEventDispatchThread()) {
            window.toFront()
            window.requestFocus()
        } else {
            SwingUtilities.invokeAndWait {
                window.toFront()
                window.requestFocus()
            }
        }
    }

    /**
     * Invokes the Compose `OnClick` semantics action on [node] directly. This bypasses the OS input
     * stack — no AWT Robot event is generated, no real cursor moves, and no platform focus/raise
     * side effects fire. It is **not** equivalent to a real user click; use [click] (which routes
     * through [RobotDriver]) when verifying input plumbing. The intended use cases are headless
     * contexts and IntelliJ tool-window flows where the OS input stack is unavailable.
     *
     * Throws [IllegalStateException] if [node] has no `OnClick` semantics action attached, or if
     * the action's invocable body is null (a semantics property without a wired-up handler).
     */
    public fun performSemanticsClick(node: AutomatorNode) {
        val accessibilityAction =
            node.semanticsNode.config.getOrNull(SemanticsActions.OnClick)
                ?: error(
                    "Node ${node.key} has no OnClick semantics action; cannot performSemanticsClick"
                )
        val action =
            accessibilityAction.action
                ?: error(
                    "Node ${node.key} declares OnClick but its action is null; " +
                        "cannot performSemanticsClick"
                )
        if (SwingUtilities.isEventDispatchThread()) {
            action.invoke()
        } else {
            SwingUtilities.invokeAndWait { action.invoke() }
        }
    }

    /**
     * Captures the given screen [region] (or the entire virtual desktop, if `null`) and returns
     * sRGB pixels as a [BufferedImage]. Delegates to [RobotDriver.screenshot] — see that method's
     * KDoc for colour-space, focus-overlay, and per-platform TCC / Wayland gotchas before using the
     * result for pixel-level assertions.
     */
    public fun screenshot(region: Rectangle? = null): BufferedImage = robotDriver.screenshot(region)

    /**
     * Captures the on-screen bounds of [node] as an sRGB [BufferedImage]. Delegates to
     * [RobotDriver.screenshot] — see that method's KDoc before using the result for pixel-level
     * assertions.
     */
    public fun screenshot(node: AutomatorNode): BufferedImage =
        robotDriver.screenshot(node.boundsOnScreen)

    /**
     * Captures the Compose surface bounds of the tracked window at [windowIndex] as an sRGB
     * [BufferedImage]. Refreshes the window list first. Delegates to [RobotDriver.screenshot] — see
     * that method's KDoc before using the result for pixel-level assertions.
     */
    public fun screenshot(windowIndex: Int): BufferedImage {
        refreshWindows()
        val trackedWindow =
            windows.getOrNull(windowIndex)
                ?: error("No tracked window at index $windowIndex (have ${windows.size})")
        return robotDriver.screenshot(trackedWindow.composeSurfaceBoundsOnScreen)
    }

    // V1 contract: queries and actions do not auto-wait. Callers must invoke waitForIdle() /
    // waitForVisualIdle() / waitForNode() explicitly when synchronisation matters. Auto-wait
    // wrapping every read/action is intentionally deferred — see the v1 issue tracker.
    private val idlingResources = CopyOnWriteArrayList<AutomatorIdlingResource>()

    public fun registerIdlingResource(resource: AutomatorIdlingResource) {
        idlingResources.addIfAbsent(resource)
    }

    public fun unregisterIdlingResource(resource: AutomatorIdlingResource) {
        idlingResources.remove(resource)
    }

    /**
     * Bracket [block] with a profiling/tracing recording, writing the captured trace to [output].
     *
     * The default [tracer] is [PerfettoTracer], which uses `androidx.tracing-wire-desktop` to write
     * standard Perfetto trace files into [output] (which is treated as a directory). Open the
     * resulting files at [ui.perfetto.dev](https://ui.perfetto.dev). Pass a custom [Tracer] (e.g. a
     * JFR adapter, in-memory event collector, etc.) to integrate with a different recorder.
     *
     * The block's return value is propagated to the caller. If the block throws, [Tracer.stop]
     * still runs so the partial trace is flushed to disk; any exception thrown by `stop` is
     * attached as a suppressed exception so the original failure stays visible.
     */
    public suspend fun <T> withTracing(
        output: Path,
        tracer: Tracer = PerfettoTracer(),
        block: suspend () -> T,
    ): T = withTracingInternal(output, tracer, block)

    public suspend fun waitForIdle(
        timeout: Duration = DEFAULT_WAIT_TIMEOUT,
        quietPeriod: Duration = DEFAULT_QUIET_PERIOD,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ) {
        rejectEdtCaller("waitForIdle")
        waitForIdleInternal(
            timeout = timeout,
            quietPeriod = quietPeriod,
            pollInterval = pollInterval,
            idlingResources = { idlingResources.toList() },
            drainEdt = ::drainEdt,
            fingerprint = ::computeUiFingerprint,
        )
    }

    public suspend fun waitForVisualIdle(
        timeout: Duration = DEFAULT_WAIT_TIMEOUT,
        stableFrames: Int = DEFAULT_STABLE_FRAMES,
        pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    ) {
        rejectEdtCaller("waitForVisualIdle")
        waitForVisualIdleInternal(
            timeout = timeout,
            stableFrames = stableFrames,
            pollInterval = pollInterval,
            frameHash = ::computeFrameHash,
        )
    }

    private fun rejectEdtCaller(name: String) {
        // The wait loops drain the EDT, snapshot semantics via invokeAndWait, and capture
        // screenshots on a bounded worker. Calling them from the EDT would either deadlock
        // (worker waiting on the EDT we hold) or skip the bounded worker entirely and lose
        // timeout enforcement. Force callers off the EDT — typically they should be running
        // on Dispatchers.Default or Dispatchers.IO with an explicit dispatcher hop into the
        // wait helper.
        check(!SwingUtilities.isEventDispatchThread()) {
            "$name must not be called from the AWT event dispatch thread; " +
                "wrap the call with withContext(Dispatchers.Default) or similar."
        }
    }

    private fun drainEdt(remainingMs: Long) {
        if (SwingUtilities.isEventDispatchThread()) return
        // Bounded drain: invokeAndWait can hang indefinitely if the EDT is deadlocked, which
        // would let waitForIdle silently overrun its timeout. We dispatch via invokeLater and
        // wait on a latch capped at min(remainingMs, EDT_DRAIN_BUDGET_MS) so neither the
        // safety budget nor the caller's overall timeout can be overrun.
        val latch = CountDownLatch(1)
        SwingUtilities.invokeLater { latch.countDown() }
        val budget = remainingMs.coerceAtMost(EDT_DRAIN_BUDGET_MS).coerceAtLeast(0)
        latch.await(budget, TimeUnit.MILLISECONDS)
    }

    private fun computeUiFingerprint(remainingMs: Long): String {
        val budget = remainingMs.coerceAtMost(FINGERPRINT_BUDGET_MS).coerceAtLeast(0)
        return runBoundedOnWorker(budget) { computeUiFingerprintUnbounded() }
            ?: "${EMPTY_FINGERPRINT_PREFIX}${System.nanoTime()}"
    }

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

    private fun computeFrameHash(remainingMs: Long): Int {
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
        val budget = remainingMs.coerceAtMost(FRAME_HASH_BUDGET_MS).coerceAtLeast(0)
        return runBoundedOnWorker(budget) {
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
        // The wait helpers reject EDT callers, so we never enter here on the EDT — meaning
        // the worker can safely invokeAndWait without deadlocking against us, and we can
        // genuinely enforce budgetMs on the sample.
        check(!SwingUtilities.isEventDispatchThread()) {
            "runBoundedOnWorker is not safe on the EDT; wait callers should have been rejected"
        }

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
            // Brief grace window to let cooperative blockers (invokeAndWait) honour the
            // interrupt and exit cleanly before we abandon the thread. Skipped when the
            // caller's remaining budget was already exhausted (budgetMs == 0): in that case
            // even a 50ms grace would push the public wait API past the caller's timeout.
            // Native non-interruptible calls still leak, but the daemon flag keeps a stuck
            // thread from holding the JVM open.
            if (budgetMs > 0) {
                latch.await(WORKER_INTERRUPT_GRACE_MS, TimeUnit.MILLISECONDS)
            }
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

    public suspend fun waitForNode(
        tag: String? = null,
        text: String? = null,
        timeout: Duration = 5.seconds,
        pollInterval: Duration = 100.milliseconds,
    ): AutomatorNode {
        // Argument validation runs before the EDT check so `waitForNode()` from the EDT still
        // surfaces the bad-input error rather than the curated EDT error — bad arguments are
        // the more actionable signal in that case.
        require(tag != null || text != null) { "Either tag or text must be specified" }
        rejectEdtCaller("waitForNode")
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

    public fun printTree(): String {
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

    public companion object {

        public fun inProcess(robotDriver: RobotDriver = RobotDriver()): ComposeAutomator =
            ComposeAutomator(WindowTracker(), SemanticsReader(), robotDriver)
    }
}

private val DEFAULT_WAIT_TIMEOUT: Duration = 5.seconds
private val DEFAULT_QUIET_PERIOD: Duration = 64.milliseconds
private val DEFAULT_POLL_INTERVAL: Duration = 16.milliseconds
private const val DEFAULT_STABLE_FRAMES: Int = 3
private const val EDT_DRAIN_BUDGET_MS: Long = 250
private const val FRAME_HASH_BUDGET_MS: Long = 500
private const val FINGERPRINT_BUDGET_MS: Long = 500
private const val WORKER_INTERRUPT_GRACE_MS: Long = 50
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

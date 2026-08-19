@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.sebastiano.spectre.core.capture.AtomicCapture
import dev.sebastiano.spectre.core.capture.AtomicCaptureBuilder
import dev.sebastiano.spectre.core.capture.CaptureNodeSnapshot
import dev.sebastiano.spectre.core.perf.ExperimentalSpectreApi
import dev.sebastiano.spectre.core.perf.RecompositionMonitor
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runInterruptible

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

    private val screenCaptureBackend: ScreenCaptureBackend =
        PlatformScreenCaptureBackend(robotDriver)

    /**
     * Snapshot of the currently tracked windows. Returns the live `TrackedWindow` collaborator
     * type, which is part of Spectre's internal escape hatch — typical users should call
     * [surfaceIds] instead. The HTTP transport in `:server` is the one in-repo consumer that needs
     * the rich type.
     */
    @InternalSpectreApi
    public val windows: List<TrackedWindow>
        get() = windowTracker.trackedWindows.value

    /** Stable surface IDs of every tracked window, in tracking order. */
    public fun surfaceIds(): List<String> = windowTracker.trackedWindows.value.map { it.surfaceId }

    public fun refreshWindows() {
        windowTracker.refresh()
    }

    /**
     * Native identity + geometry for every tracked window, for out-of-process recorders.
     *
     * See [WindowIdentitySnapshot] for coordinate spaces and the crop-required flag (spike
     * constraint #5). Always refreshes the window list first so results match the live UI.
     */
    @InternalSpectreApi
    public fun windowIdentities(): List<WindowIdentitySnapshot> {
        refreshWindows()
        return windows.mapIndexed { index, tracked ->
            WindowIdentityResolver.resolve(index, tracked)
        }
    }

    /**
     * Native identity + geometry for a single tracked window by index.
     *
     * @throws IllegalArgumentException if [windowIndex] is out of range after refresh.
     */
    @InternalSpectreApi
    public fun windowIdentity(windowIndex: Int): WindowIdentitySnapshot {
        val all = windowIdentities()
        return all.getOrNull(windowIndex)
            ?: throw IllegalArgumentException(
                "No tracked window at index $windowIndex (have ${all.size})"
            )
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

    /** Moves the pointer to [node]'s centre without pressing a button. */
    public suspend fun moveTo(node: AutomatorNode) {
        val center = node.centerOnScreen
        robotDriver.moveTo(center.x, center.y)
    }

    /**
     * Moves the pointer to screen coordinates (same space as [swipe]) without pressing a button.
     */
    public suspend fun moveTo(x: Int, y: Int) {
        robotDriver.moveTo(x, y)
    }

    /**
     * Moves the pointer by [deltaX], [deltaY] relative to the last Spectre-issued pointer position
     * on this automator's [RobotDriver]. Throws [IllegalStateException] if no Spectre pointer move
     * has happened yet.
     */
    public suspend fun moveBy(deltaX: Int, deltaY: Int) {
        robotDriver.moveBy(deltaX, deltaY)
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

    public suspend fun pasteText(text: String) {
        robotDriver.pasteText(text)
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
     *
     * **Dimensions.** One pixel per logical screen unit, so image coordinates equal screen
     * coordinates even on a scaled display. Use [screenshotAtDeviceScale] when you want the
     * screen-pixel resolution Spectre's still artifacts and recordings use.
     */
    public fun screenshot(region: Rectangle? = null): BufferedImage =
        screenCaptureBackend.captureRegion(region)

    /**
     * Captures the given screen [region] (or the entire virtual desktop, if `null`) at the
     * display's **device** resolution, and returns sRGB pixels as a [BufferedImage].
     *
     * Identical to [screenshot] except for dimensions: a 400x300dp region yields an 800x600 pixel
     * image on a 2x display instead of 400x300, matching the resolution `Recorder` writes for the
     * same region. Delegates to [RobotDriver.screenshotAtDeviceScale] — see that method's KDoc for
     * the mixed-density rule and the same colour-space / TCC / Wayland caveats as [screenshot].
     *
     * This backs Spectre's still artifacts. Use [screenshot] when you want image coordinates to
     * equal screen coordinates.
     */
    public fun screenshotAtDeviceScale(region: Rectangle? = null): BufferedImage =
        screenCaptureBackend.captureStillRegion(region)

    /**
     * Captures the on-screen bounds of [node] as an sRGB [BufferedImage].
     *
     * Captures through the optional native window backend. If that backend cannot identify or
     * capture the tracked window, this method fails rather than substituting a screen-region crop.
     * Use [screenshot] with an explicit [Rectangle] when screen-region capture is intended.
     *
     * **Dimensions.** Screen pixels, not dp: [node] bounds of 400x300dp yield an 800x600 pixel
     * image on a 2x display. Divide by the window's density scale to get back to logical units.
     */
    public fun screenshot(node: AutomatorNode): BufferedImage {
        val geometry = readOnEdt {
            ScreenshotGeometry(
                node.boundsOnScreen,
                node.trackedWindow.window.bounds,
                frameInsets(node.trackedWindow.window),
            )
        }
        return screenshotTrackedRegion(
            node.trackedWindow,
            geometry.region,
            geometry.windowBounds,
            geometry.frameInsets,
        )
    }

    /**
     * Captures the Compose surface bounds of the tracked window at [windowIndex] as an sRGB
     * [BufferedImage]. Refreshes the window list first.
     *
     * Captures through the optional native window backend. If that backend cannot identify or
     * capture the tracked window, this method fails rather than substituting a screen-region crop.
     * Use [screenshot] with an explicit [Rectangle] when screen-region capture is intended.
     *
     * **Dimensions.** Screen pixels, not dp: a 1600x1000dp Compose surface yields a 3200x2000 pixel
     * image on a 2x display, matching what `Recorder` writes for the same window.
     */
    public fun screenshot(windowIndex: Int): BufferedImage {
        refreshWindows()
        val trackedWindow = requireShowingTrackedWindow(windowIndex)
        val geometry = readOnEdt {
            ScreenshotGeometry(
                trackedWindow.composeSurfaceBoundsOnScreen,
                trackedWindow.window.bounds,
                frameInsets(trackedWindow.window),
            )
        }
        return screenshotTrackedRegion(
            trackedWindow,
            geometry.region,
            geometry.windowBounds,
            geometry.frameInsets,
        )
    }

    /**
     * Atomic capture of one window: semantics tree snapshot + window PNG taken back-to-back.
     *
     * The tree (including node geometry) is read first; the PNG is taken immediately afterward
     * without returning control to the caller. Prefer settling with [waitForVisualIdle] /
     * [waitForIdle] first so the pair stays decision-grade across any native still-helper latency.
     * When `spectre-recording` is present the PNG uses the same **window-scoped** native still path
     * as [screenshot] with a [windowIndex] (fails loudly rather than substituting a screen-region
     * crop after a native failure). Without that backend (e.g. inject payload that omits
     * recording), the still is a Robot region capture of the Compose surface.
     *
     * The PNG is **screen-pixel** sized, not dp sized: a 1600x1000dp window on a 2x display
     * produces a 3200x2000 pixel PNG, the same resolution `Recorder` writes for that window.
     * `capture.json` reports the exact PNG size as `window.imageWidth` / `imageHeight`, keeps
     * `window.boundsScreen` in logical units, and records the density as `densityScaleX` /
     * `densityScaleY`.
     *
     * Node bounds in the returned document use **image-pixel space of the PNG as primary** and
     * screen space as secondary. Callers that want files on disk should pass the result through
     * [dev.sebastiano.spectre.core.capture.CaptureArtifactsWriter].
     *
     * Does **not** auto-call [waitForVisualIdle] or [waitForIdle] — settle the UI first when that
     * matters, matching [screenshot]. Exact [windowIndex] only (must be showing); attach clients
     * that want "first showing" resolve via [windows] + `isShowing` before calling.
     */
    public fun capture(windowIndex: Int = 0): AtomicCapture {
        val startedAt = TimeSource.Monotonic.markNow()
        refreshWindows()
        val trackedWindow = requireShowingTrackedWindow(windowIndex)
        // Freeze capture region, density, node properties, and screen geometry in one EDT pass
        // *before* taking the PNG so JSON and pixels cannot describe different window layouts.
        data class PreCaptureSnapshot(
            val captureRegion: Rectangle,
            val windowBounds: Rectangle,
            val frameInsets: java.awt.Insets,
            val densityScaleX: Double,
            val densityScaleY: Double,
            val nodeSnapshots: List<CaptureNodeSnapshot>,
        )
        val pre = readOnEdt {
            val region = trackedWindow.composeSurfaceBoundsOnScreen
            val transform = trackedWindow.window.graphicsConfiguration.defaultTransform
            PreCaptureSnapshot(
                captureRegion = region,
                windowBounds = Rectangle(trackedWindow.window.bounds),
                frameInsets = frameInsets(trackedWindow.window),
                densityScaleX = transform.scaleX,
                densityScaleY = transform.scaleY,
                nodeSnapshots =
                    semanticsReader.readAllNodes(listOf(trackedWindow)).map { node ->
                        CaptureNodeSnapshot(
                            key = node.key.toString(),
                            testTag = node.testTag,
                            text = node.text,
                            texts = node.texts,
                            editableText = node.editableText,
                            contentDescription = node.contentDescription,
                            role = node.role?.toString(),
                            enabled = !node.isDisabled,
                            clickable = node.isClickable,
                            focused = node.isFocused,
                            selected = node.isSelected,
                            boundsScreen = node.bothBounds().onScreen,
                        )
                    },
            )
        }
        // Window-scoped still when the recording native bridge is present (#355). Injected core
        // excludes recording, so fall back to an explicit region capture only when the bridge is
        // absent — never as a silent substitute after a failed native still.
        val windowCapture =
            if (
                isNativeWindowCaptureAvailable(
                    allowsPlatformCapture = robotDriver.allowsPlatformCapture
                )
            ) {
                screenshotTrackedRegionCapture(
                    trackedWindow,
                    pre.captureRegion,
                    pre.windowBounds,
                    pre.frameInsets,
                )
            } else {
                WindowCapture(
                    image = screenCaptureBackend.captureStillRegion(pre.captureRegion),
                    boundsOnScreen = pre.captureRegion,
                )
            }
        return AtomicCaptureBuilder.build(
            windowIndex = windowIndex,
            trackedWindow = trackedWindow,
            nodeSnapshots = pre.nodeSnapshots,
            image = windowCapture.image,
            captureRegion = windowCapture.boundsOnScreen,
            densityScaleX = pre.densityScaleX,
            densityScaleY = pre.densityScaleY,
            startedAt = startedAt,
        )
    }

    // Queries and actions do not auto-wait. Callers must invoke waitForIdle() /
    // waitForVisualIdle() / waitForNode() explicitly when synchronisation matters. Auto-wait
    // wrapping every read/action remains intentionally out of scope — the explicit-wait shape
    // keeps the public surface predictable and lets callers choose the synchronisation strategy
    // that fits their test.
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

    /**
     * Starts a [dev.sebastiano.spectre.core.perf.RecompositionMonitor] that observes recomposition
     * counts across every Compose surface this automator currently tracks, plus any surfaces that
     * appear later. The monitor piggybacks on the existing `WindowTracker` flow — every
     * [refreshWindows] call (and `tree()`, which refreshes internally) emits the new surface set,
     * and the monitor reconciles its CompositionObserver attachments automatically.
     *
     * Note: query helpers like [findByTestTag] / [findByText] read the *current* tracked-windows
     * snapshot without driving a refresh, so they do not by themselves discover newly opened
     * windows. Call [refreshWindows] (or [tree], or [waitForIdle], all of which refresh) after
     * opening a new window if you need the monitor to attach to it before continuing.
     *
     * The caller owns the returned monitor's lifecycle: [RecompositionMonitor.close] cancels its
     * internal scope and disposes every CompositionObserver handle. Failing to close it leaks the
     * subscription against the tracker.
     */
    @ExperimentalSpectreApi
    public fun monitorRecompositions(
        windowDuration: Duration = RecompositionMonitor.DEFAULT_WINDOW
    ): RecompositionMonitor {
        val monitor = RecompositionMonitor(windowDuration)
        monitor.subscribeTo(windowTracker)
        return monitor
    }

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
        val frameHasher =
            BoundedFrameHasher(
                steadyStateBudgetMs = FRAME_HASH_BUDGET_MS,
                sample = ::sampleFrameHash,
            )
        waitForVisualIdleInternal(
            timeout = timeout,
            stableFrames = stableFrames,
            pollInterval = pollInterval,
            frameHash = frameHasher::hash,
        )
    }

    /** Exact index; fails if missing or not showing (no silent remap). */
    private fun requireShowingTrackedWindow(windowIndex: Int): TrackedWindow {
        val preferred =
            windows.getOrNull(windowIndex)
                ?: error("No tracked window at index $windowIndex (have ${windows.size})")
        if (!preferred.window.isShowing) {
            error(
                "Tracked window at index $windowIndex is not showing " +
                    "(surfaceId=${preferred.surfaceId}); wait until it is visible before " +
                    "screenshot/capture"
            )
        }
        return preferred
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

    private suspend fun sampleFrameHash(budgetMs: Long): Int? {
        // Hash tracked Compose surfaces independently: a virtual-desktop capture would let
        // unrelated windows, notifications, or the cursor outside the app reset the streak.
        // Returning null for no surfaces or a budget expiry makes the per-wait hasher produce a
        // changing value, so an unsampleable UI cannot be reported as visually idle.
        // Native capture can take seconds on its first use, so BoundedFrameHasher gives the cold
        // sample the wait's remaining budget. After the first completed sample, this worker is
        // capped at FRAME_HASH_BUDGET_MS: an unexpectedly hung EDT or capture API still cannot
        // out-block waitForVisualIdle's overall deadline.
        //
        // Prefer window-scoped pixels when the recording native still bridge is present (#355):
        // one-shot helper startup is paid on the cold sample; steady-state budget is sized for a
        // warm one-shot still (see FRAME_HASH_BUDGET_MS), not a continuous frame stream.
        return runInterruptible {
            runBoundedOnWorker(budgetMs) {
                refreshWindows()
                val surfaces = trackedComposeSurfaces()
                if (surfaces.isEmpty()) {
                    null
                } else {
                    val geometry = readOnEdt {
                        surfaces.associate { (window, _) ->
                            window to
                                (Rectangle(window.window.bounds) to frameInsets(window.window))
                        }
                    }
                    hashTrackedSurfacesForVisualIdle(
                        surfaces = surfaces,
                        windowBoundsFor = { geometry.getValue(it).first },
                        frameInsetsFor = { geometry.getValue(it).second },
                        backend = screenCaptureBackend,
                        nativeWindowCaptureAvailable =
                            isNativeWindowCaptureAvailable(
                                allowsPlatformCapture = robotDriver.allowsPlatformCapture
                            ),
                    )
                }
            }
        }
    }

    private fun trackedComposeSurfaces(): List<Pair<TrackedWindow, Rectangle>> = readOnEdt {
        windows.mapNotNull { window ->
            runCatching { window.composeSurfaceBoundsOnScreen }
                .getOrNull()
                ?.takeIf { !it.isEmpty }
                ?.let { window to it }
        }
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
        try {
            return if (latch.await(budgetMs, TimeUnit.MILLISECONDS)) {
                requireNotNull(resultRef.get()) {
                        "spectre-bounded-worker counted down the latch without publishing a Result"
                    }
                    .getOrThrow()
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
        } catch (e: InterruptedException) {
            thread.interrupt()
            throw e
        }
    }

    private fun screenshotTrackedRegion(
        trackedWindow: TrackedWindow,
        region: Rectangle,
        windowBounds: Rectangle,
        frameInsets: java.awt.Insets,
    ): BufferedImage {
        return screenshotTrackedRegionCapture(trackedWindow, region, windowBounds, frameInsets)
            .image
    }

    private data class ScreenshotGeometry(
        val region: Rectangle,
        val windowBounds: Rectangle,
        val frameInsets: java.awt.Insets,
    )

    private fun screenshotTrackedRegionCapture(
        trackedWindow: TrackedWindow,
        region: Rectangle,
        windowBounds: Rectangle = trackedWindow.window.bounds,
        frameInsets: java.awt.Insets = frameInsets(trackedWindow.window),
    ): WindowCapture =
        windowStillForRegion(
            screenCaptureBackend.captureWindow(trackedWindow, windowBounds, frameInsets),
            region,
        )

    private fun frameInsets(window: java.awt.Window): java.awt.Insets =
        (window as? java.awt.Frame)?.insets ?: java.awt.Insets(0, 0, 0, 0)

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

        public fun inProcess(
            robotDriver: RobotDriver = RobotDriver(),
            discoverWindows: Boolean = true,
        ): ComposeAutomator =
            ComposeAutomator(
                windowTracker = if (discoverWindows) WindowTracker() else WindowTracker.empty(),
                semanticsReader = SemanticsReader(),
                robotDriver = robotDriver,
            )
    }
}

private val DEFAULT_WAIT_TIMEOUT: Duration = 5.seconds
private val DEFAULT_QUIET_PERIOD: Duration = 64.milliseconds
private val DEFAULT_POLL_INTERVAL: Duration = 16.milliseconds
private const val DEFAULT_STABLE_FRAMES: Int = 3
private const val EDT_DRAIN_BUDGET_MS: Long = 250
// Steady-state sample budget after the cold sample. Warm one-shot native stills are typically
// a few hundred ms (Issue14: ~400ms hot on macOS); keep headroom for multi-surface waits and
// slower Windows/Linux helper startup without treating a static UI as permanently unstable.
private const val FRAME_HASH_BUDGET_MS: Long = 2_000
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

package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class RobotDriver
internal constructor(
    private val robot: RobotAdapter = AwtRobotAdapter(),
    private val clipboard: ClipboardAdapter = SystemClipboardAdapter(),
    private val tccGuard: MacOsTccGuard = defaultTccGuardFor(robot),
) {

    // Public surface: callers may instantiate without arguments (defaults to a fresh
    // AWT Robot + system clipboard) or hand in an existing Robot. The internal
    // adapter-injecting constructor is reserved for tests within this module.
    constructor() : this(AwtRobotAdapter(), SystemClipboardAdapter())

    constructor(robot: Robot) : this(AwtRobotAdapter(robot))

    suspend fun click(screenX: Int, screenY: Int) {
        tccGuard.requireAccessibility()
        runOffEdt {
            robot.mouseMove(screenX, screenY)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    suspend fun doubleClick(screenX: Int, screenY: Int) {
        tccGuard.requireAccessibility()
        runOffEdt {
            robot.mouseMove(screenX, screenY)
            repeat(DOUBLE_CLICK_COUNT) {
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }
        }
    }

    suspend fun longClick(
        screenX: Int,
        screenY: Int,
        holdFor: Duration = DEFAULT_LONG_CLICK_DURATION,
    ) {
        tccGuard.requireAccessibility()
        runOffEdt {
            robot.mouseMove(screenX, screenY)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try {
                delay(holdFor)
            } finally {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }
        }
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        steps: Int = DEFAULT_SWIPE_STEPS,
        duration: Duration = DEFAULT_SWIPE_DURATION,
    ) {
        tccGuard.requireAccessibility()
        runOffEdt {
            val points = interpolateSwipePoints(startX, startY, endX, endY, steps)
            val pausePerStepMs = swipePauseMillis(duration, steps, autoDelayMs = robot.autoDelayMs)
            val firstPoint = points.first()
            robot.mouseMove(firstPoint.x, firstPoint.y)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try {
                for (point in points.drop(1)) {
                    robot.mouseMove(point.x, point.y)
                    if (pausePerStepMs > 0) {
                        delay(pausePerStepMs.milliseconds)
                    }
                }
            } finally {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }
        }
    }

    suspend fun typeText(text: String) {
        tccGuard.requireAccessibility()
        runOffEdt {
            val previousContents = runCatching { clipboard.getContents() }.getOrNull()
            try {
                clipboard.setContents(StringSelection(text))
                // OS pasteboard writes (notably macOS NSPasteboard) are asynchronous:
                // setContents returns immediately, but readers can observe the previous value
                // for a short window. If we dispatch Cmd+V before the pasteboard surfaces our
                // text, the focused field's paste handler reads stale (often empty) contents
                // and the typed characters never land. Poll the clipboard until it reports our
                // string, with a bounded budget so a misbehaving clipboard adapter cannot
                // wedge the call indefinitely. Skip the poll for adapters that don't support
                // read-back so that path doesn't burn the full settle timeout against a
                // permanently-null reader.
                if (clipboard.supportsRead) {
                    awaitClipboardContents(
                        text,
                        CLIPBOARD_SETTLE_TIMEOUT_MS,
                        CLIPBOARD_SETTLE_POLL_MS,
                    )
                }
                val modifier = shortcutModifierKeyCode(detectMacOs())
                robot.keyPress(modifier)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(modifier)
                if (clipboard.supportsRead && !SwingUtilities.isEventDispatchThread()) {
                    // Drain queued AWT events (KEY_PRESSED/RELEASED + Compose's input
                    // pipeline) so the paste handler has a chance to read the clipboard before
                    // we restore it. Without this, the finally block can clobber the clipboard
                    // mid-paste and the field receives empty text — the exact symptom that
                    // flaked the live validation. One waitForIdle pump is not enough:
                    // Compose's paste action runs on its own coroutine dispatcher which posts
                    // back to the EDT, so we pump a few times and also wait a short interval
                    // to let the OS-side paste read complete before we clobber the clipboard
                    // contents. Skip the whole block when called on the EDT (the synthetic
                    // adapter's runOffEdt short-circuit lets it stay on EDT), where
                    // waitForIdle is a no-op and the post-paste settle would just add wall-
                    // clock latency for nothing — same skip the pre-suspend code applied. Skip
                    // for adapters that don't support clipboard read-back too — there's no real
                    // paste handler to drain.
                    repeat(POST_PASTE_EDT_PUMPS) { robot.waitForIdle() }
                    delay(POST_PASTE_SETTLE_MS.milliseconds)
                    repeat(POST_PASTE_EDT_PUMPS) { robot.waitForIdle() }
                }
            } finally {
                if (previousContents != null) {
                    runCatching { clipboard.setContents(previousContents) }
                }
            }
        }
    }

    private suspend fun awaitClipboardContents(expected: String, timeoutMs: Long, pollMs: Long) {
        val deadline = System.nanoTime() + timeoutMs * NANOS_PER_MILLI
        while (true) {
            val current =
                runCatching { clipboard.getContents()?.getTransferData(DataFlavor.stringFlavor) }
                    .recover { error ->
                        // UnsupportedFlavorException means another writer just clobbered the
                        // clipboard with non-string data — treat as "not yet" and keep polling.
                        if (error is UnsupportedFlavorException) null else throw error
                    }
                    .getOrNull() as? String
            if (current == expected) return
            if (System.nanoTime() >= deadline) return
            delay(pollMs.milliseconds)
        }
    }

    suspend fun clearAndTypeText(text: String) {
        tccGuard.requireAccessibility()
        val selectAllModifier = shortcutModifierKeyCode(detectMacOs())
        runOffEdt {
            robot.keyPress(selectAllModifier)
            robot.keyPress(KeyEvent.VK_A)
            robot.keyRelease(KeyEvent.VK_A)
            robot.keyRelease(selectAllModifier)
            robot.keyPress(KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(KeyEvent.VK_BACK_SPACE)
        }
        typeText(text)
    }

    /**
     * Dispatches a vertical mouse-wheel scroll at the given screen coordinates. Positive
     * [wheelClicks] scrolls down (away from the user, like physical wheel rotation toward the
     * desk); negative scrolls up. Drives Compose's `Modifier.scrollable` and lazy-list scroll on
     * desktop, which respond to wheel events rather than touch-style drag gestures.
     */
    suspend fun scrollWheel(screenX: Int, screenY: Int, wheelClicks: Int) {
        tccGuard.requireAccessibility()
        runOffEdt {
            robot.mouseMove(screenX, screenY)
            robot.mouseWheel(wheelClicks)
        }
    }

    suspend fun pressKey(keyCode: Int, modifiers: Int = 0) {
        tccGuard.requireAccessibility()
        runOffEdt {
            val modifierKeys = modifierMaskToKeyCodes(modifiers)
            for (mod in modifierKeys) robot.keyPress(mod)
            robot.keyPress(keyCode)
            robot.keyRelease(keyCode)
            for (mod in modifierKeys.reversed()) robot.keyRelease(mod)
        }
    }

    /**
     * Captures the given screen [region] (or the entire virtual desktop, if `null`) and returns the
     * pixels as a [BufferedImage].
     *
     * **Colour space.** The returned image is sRGB (`TYPE_INT_ARGB` / sRGB `ColorModel`), matching
     * `java.awt.Robot.createScreenCapture`'s contract. Pixel values are post-display-pipeline: what
     * the OS composited and what the framebuffer holds, not the source values your Compose code
     * passed to `Color(...)`. Two consequences worth knowing for pixel assertions:
     *
     * - Compose Foundation's default `LocalIndication` paints a persistent ~10% black overlay on
     *   focused / pressed `Modifier.clickable` elements. A blue button at `Color(0x33, 0x66, 0xCC)`
     *   captures as roughly `#2E5CB7` (`0.9 × #3366CC + 0.1 × #000000`) once it's been clicked.
     *   Easy to mistake for a render bug. Avoid by asserting against a non-interactive element, or
     *   shifting focus before capture.
     * - 8-bit channels mean ±1-2 of rounding noise from the sRGB → display-gamma → pixel roundtrip.
     *   Wide-gamut display profiles can amplify this further. Use a small per-channel tolerance for
     *   any equality-style assertion.
     *
     * On macOS, the underlying `java.awt.Robot.createScreenCapture` requires the wrapping process
     * to hold Screen Recording TCC permission. Without it the call returns silently with an
     * all-black image rather than throwing. The default [RobotDriver] guards against this: on the
     * first `screenshot` call from a Mac, it probes Screen Recording TCC and throws
     * [IllegalStateException] with an actionable remediation message if the probe sees an all-black
     * capture. The probe and the resulting status are cached, so subsequent calls have no extra
     * cost. Use [headless] to opt out entirely.
     *
     * On Linux, captures work against X11 (real Xorg or XWayland-bridged X clients) but not against
     * native Wayland surfaces — Wayland's security model forbids cross-process framebuffer reads.
     * For headless capture in CI use `xvfb-run` to provide a virtual Xorg display.
     *
     * The returned [BufferedImage] is owned by the caller and safe to read, mutate, or pass to
     * downstream pipelines.
     *
     * ## Capture scope
     *
     * `region` is passed through to `java.awt.Robot.createScreenCapture` unchanged. It is **not**
     * constrained to the app under test: any rectangle the JVM can see — including other
     * applications, sensitive on-screen content, and unrelated windows — is fair game. With `region
     * = null` the capture covers the **entire virtual desktop**. Treat the returned pixels as
     * OS-visible content rather than as a slice of the test surface. A narrower per-window /
     * per-node screenshot API is tracked under #96.
     */
    fun screenshot(region: Rectangle? = null): BufferedImage {
        tccGuard.requireScreenRecording()
        val captureRegion = region ?: virtualDesktopBounds()
        return robot.createScreenCapture(captureRegion)
    }

    private suspend fun runOffEdt(block: suspend () -> Unit) = runOffEdt(robot, block)

    companion object {

        /**
         * Returns a [RobotDriver] that throws [UnsupportedOperationException] on every input,
         * clipboard, and screenshot call. [WindowTracker] / [SemanticsReader] are untouched, so
         * semantics-tree reads still work.
         *
         * Reach for this in headless CI or any context where real OS I/O is unavailable, **and**
         * the code under test is genuinely read-only (semantics queries, rule/extension lifecycle
         * wiring). The loud-failure contract means an accidental `automator.click(...)` /
         * `typeText(...)` / `screenshot(...)` surfaces at the call site instead of silently
         * dropping the operation and tripping a downstream assertion three layers later.
         *
         * If you do need real input or capture in a non-OS environment, use
         * [RobotDriver.Companion.synthetic] against a known root window for synthetic AWT events,
         * or invoke `SemanticsActions.OnClick` directly on the target node for click-only flows.
         */
        fun headless(): RobotDriver =
            RobotDriver(
                HeadlessThrowingRobotAdapter,
                HeadlessThrowingClipboardAdapter,
                MacOsTccGuard.noop(),
            )
    }
}

/**
 * Builds the default [MacOsTccGuard] for a given adapter. Returns a real probe-backed guard only
 * when the adapter delegates to a real `java.awt.Robot` AND we're running on macOS — every other
 * combination (synthetic adapter, headless-throwing adapter, non-macOS host) gets the noop guard so
 * the probe never touches an OS that isn't being driven by Robot.
 */
internal fun defaultTccGuardFor(adapter: RobotAdapter): MacOsTccGuard =
    if (adapter.gatesMacOsTcc && detectMacOs()) {
        MacOsTccGuard(
            accessibilityProbe = ::osascriptAccessibilityProbe,
            screenRecordingProbe = { robotScreenRecordingProbe(adapter) },
        )
    } else {
        MacOsTccGuard.noop()
    }

internal interface RobotAdapter {

    val autoDelayMs: Int

    /**
     * `true` when the adapter's input methods must run off the AWT EDT — the real
     * `java.awt.Robot.mouseMove`/`keyPress` block when called from the EDT, so [RobotDriver]
     * marshals them onto a worker thread.
     *
     * `false` when the adapter dispatches to its own thread internally (synthetic, headless), in
     * which case [RobotDriver] runs the call inline. The worker-thread path used to deadlock
     * synthetic callers invoked from the EDT: the EDT-blocking `Thread.join()` waited for a worker
     * that was itself stuck in `SwingUtilities.invokeAndWait` waiting for the EDT.
     */
    val requiresOffEdt: Boolean

    /**
     * `true` when this adapter delegates input/capture to the real OS `java.awt.Robot`, and
     * therefore needs to be gated on macOS TCC entitlements (Accessibility for input, Screen
     * Recording for capture). The synthetic and headless-throwing adapters bypass `Robot` entirely,
     * so they declare `false` and skip the guard regardless of platform.
     */
    val gatesMacOsTcc: Boolean
        get() = false

    fun mouseMove(x: Int, y: Int)

    fun mousePress(buttons: Int)

    fun mouseRelease(buttons: Int)

    fun keyPress(keyCode: Int)

    fun keyRelease(keyCode: Int)

    fun mouseWheel(wheelClicks: Int)

    fun createScreenCapture(region: Rectangle): BufferedImage

    fun waitForIdle()
}

internal interface ClipboardAdapter {

    /**
     * `true` when [getContents] reads back values written by [setContents]. Adapters that don't
     * read back (a one-way write-only sink, or one that always throws) should return `false` so
     * callers can skip clipboard-settle polling that would otherwise spin its full timeout against
     * a permanently-null or always-throwing reader.
     */
    val supportsRead: Boolean
        get() = true

    fun getContents(): Transferable?

    fun setContents(contents: Transferable)
}

private class AwtRobotAdapter(private val robot: Robot = createAwtRobot()) : RobotAdapter {

    override val autoDelayMs: Int
        get() = robot.autoDelay

    // Real `java.awt.Robot` calls block when invoked on the EDT, so RobotDriver must marshal
    // them onto a worker thread.
    override val requiresOffEdt: Boolean = true

    // The only adapter that actually drives the OS through Robot, so the only one that ever
    // hits macOS TCC. Synthetic and headless-throwing adapters inherit the default `false`.
    override val gatesMacOsTcc: Boolean = true

    override fun mouseMove(x: Int, y: Int) = robot.mouseMove(x, y)

    override fun mousePress(buttons: Int) = robot.mousePress(buttons)

    override fun mouseRelease(buttons: Int) = robot.mouseRelease(buttons)

    override fun keyPress(keyCode: Int) = robot.keyPress(keyCode)

    override fun keyRelease(keyCode: Int) = robot.keyRelease(keyCode)

    override fun mouseWheel(wheelClicks: Int) = robot.mouseWheel(wheelClicks)

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        robot.createScreenCapture(region)

    override fun waitForIdle() = robot.waitForIdle()
}

private object HeadlessThrowingRobotAdapter : RobotAdapter {

    override val autoDelayMs: Int = 0

    // We throw before reaching any worker-hop logic, so the off-EDT marshal is irrelevant; pick
    // the cheaper inline path so EDT callers see the throw without a thread spin-up.
    override val requiresOffEdt: Boolean = false

    override fun mouseMove(x: Int, y: Int): Unit = throwHeadless("mouseMove")

    override fun mousePress(buttons: Int): Unit = throwHeadless("mousePress")

    override fun mouseRelease(buttons: Int): Unit = throwHeadless("mouseRelease")

    override fun keyPress(keyCode: Int): Unit = throwHeadless("keyPress")

    override fun keyRelease(keyCode: Int): Unit = throwHeadless("keyRelease")

    override fun mouseWheel(wheelClicks: Int): Unit = throwHeadless("mouseWheel")

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        throwHeadless("createScreenCapture")

    // waitForIdle is purely a synchronisation barrier — it produces no observable side effect, so
    // making it a no-op keeps internal pump loops (e.g. the post-paste drain in typeText) callable
    // without triggering throws on a path where they'd be unreachable anyway.
    override fun waitForIdle() = Unit
}

private object HeadlessThrowingClipboardAdapter : ClipboardAdapter {

    // The driver short-circuits clipboard polling and post-paste settles when this is `false`.
    // Even though every read/write throws, leaving it `false` keeps the typeText fast path
    // honest: callers reach the throwing setContents directly without first burning the
    // clipboard-settle timeout against an adapter that will never read back.
    override val supportsRead: Boolean = false

    override fun getContents(): Transferable = throwHeadless("clipboard read")

    override fun setContents(contents: Transferable): Unit = throwHeadless("clipboard write")
}

private fun throwHeadless(operation: String): Nothing =
    throw UnsupportedOperationException(
        "RobotDriver.headless() does not perform real I/O — $operation was called. Use " +
            "RobotDriver.synthetic(rootWindow) for a synthetic-event driver, or invoke " +
            "SemanticsActions.OnClick directly on the target node for click-only flows."
    )

internal class SystemClipboardAdapter : ClipboardAdapter {
    // Lazy: looking up the system clipboard at construction time would couple every
    // RobotDriver instantiation to clipboard availability, breaking mouse/screenshot-only
    // automation runs in restricted environments.
    private val clipboard by lazy { Toolkit.getDefaultToolkit().systemClipboard }

    override fun getContents(): Transferable? = clipboard.getContents(null)

    override fun setContents(contents: Transferable) {
        clipboard.setContents(contents, null)
    }
}

private fun createAwtRobot(): Robot =
    Robot().apply {
        autoDelay = DEFAULT_AUTO_DELAY_MS
        isAutoWaitForIdle = false
    }

private suspend fun runOffEdt(robot: RobotAdapter, block: suspend () -> Unit) {
    // Adapters that don't need the off-EDT marshal (synthetic, headless-throwing) can run
    // inline even on the EDT — switching to another dispatcher would force the synthetic
    // adapter to bounce IO → EDT for every dispatch when the caller is already on the EDT,
    // and used to deadlock the older thread-spawning marshal entirely (the EDT-blocking
    // `Thread.join()` waited for a worker stuck in `SwingUtilities.invokeAndWait`).
    if (!robot.requiresOffEdt || !SwingUtilities.isEventDispatchThread()) {
        block()
        return
    }
    // Real `java.awt.Robot` calls block. `Dispatchers.IO` is sized for blocking I/O work and
    // suspends the calling EDT coroutine without parking the EDT thread.
    withContext(Dispatchers.IO) { block() }
}

internal fun shortcutModifierKeyCode(isMacOs: Boolean): Int =
    if (isMacOs) KeyEvent.VK_META else KeyEvent.VK_CONTROL

internal fun modifierMaskToKeyCodes(mask: Int): List<Int> = buildList {
    if (mask and InputEvent.CTRL_DOWN_MASK != 0) add(KeyEvent.VK_CONTROL)
    if (mask and InputEvent.SHIFT_DOWN_MASK != 0) add(KeyEvent.VK_SHIFT)
    if (mask and InputEvent.ALT_DOWN_MASK != 0) add(KeyEvent.VK_ALT)
    if (mask and InputEvent.META_DOWN_MASK != 0) add(KeyEvent.VK_META)
}

internal fun swipePauseMillis(duration: Duration, steps: Int, autoDelayMs: Int): Long {
    require(steps > 0) { "steps must be positive" }
    val perStepBudgetMs = duration.inWholeMilliseconds / steps
    return (perStepBudgetMs - autoDelayMs).coerceAtLeast(0)
}

internal fun interpolateSwipePoints(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    steps: Int,
): List<Point> {
    require(steps > 0) { "steps must be positive" }
    return buildList {
        for (step in 0..steps) {
            val progress = step.toFloat() / steps
            add(Point(lerp(startX, endX, progress), lerp(startY, endY, progress)))
        }
    }
}

internal fun detectMacOs(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

private fun virtualDesktopBounds(): Rectangle {
    // GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices throws HeadlessException
    // when the JVM is running with -Djava.awt.headless=true (e.g. CI). Fall back to a 1×1
    // rectangle here so the bounds lookup itself doesn't throw; the underlying adapter's
    // createScreenCapture is what decides whether the call is supported in this environment.
    if (GraphicsEnvironment.isHeadless()) return Rectangle(0, 0, 1, 1)

    val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val devices = ge.screenDevices
    var bounds = devices.first().defaultConfiguration.bounds
    for (i in 1 until devices.size) {
        bounds = bounds.union(devices[i].defaultConfiguration.bounds)
    }
    return bounds
}

private fun lerp(start: Int, end: Int, progress: Float): Int =
    (start + ((end - start) * progress)).toInt()

private const val DEFAULT_AUTO_DELAY_MS = 10
private const val DOUBLE_CLICK_COUNT = 2
private const val DEFAULT_SWIPE_STEPS = 12
private val DEFAULT_SWIPE_DURATION: Duration = 200.milliseconds
private val DEFAULT_LONG_CLICK_DURATION: Duration = 500.milliseconds

// Bounded budget for the OS pasteboard write to surface — generous enough to absorb cold-JVM
// macOS NSPasteboard latency (typically <50ms but can spike on a freshly woken machine), short
// enough that a wedged clipboard adapter cannot delay the call indefinitely.
private const val CLIPBOARD_SETTLE_TIMEOUT_MS: Long = 1_000L
private const val CLIPBOARD_SETTLE_POLL_MS: Long = 5L
private const val NANOS_PER_MILLI: Long = 1_000_000L

// After dispatching Cmd+V we drain the EDT a few times to let Compose's paste-action coroutine
// post back, then sleep briefly so the OS clipboard read completes, then drain again. The total
// cost (a few ms) is dwarfed by the cold-JVM AWT/Compose startup latency that already dominates
// any test using typeText.
private const val POST_PASTE_EDT_PUMPS: Int = 3
private const val POST_PASTE_SETTLE_MS: Long = 50L

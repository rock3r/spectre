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

class RobotDriver
internal constructor(
    private val robot: RobotAdapter = AwtRobotAdapter(),
    private val clipboard: ClipboardAdapter = SystemClipboardAdapter(),
) {

    // Public surface: callers may instantiate without arguments (defaults to a fresh
    // AWT Robot + system clipboard) or hand in an existing Robot. The internal
    // adapter-injecting constructor is reserved for tests within this module.
    constructor() : this(AwtRobotAdapter(), SystemClipboardAdapter())

    constructor(robot: Robot) : this(AwtRobotAdapter(robot))

    fun click(screenX: Int, screenY: Int) = runOffEdt {
        robot.mouseMove(screenX, screenY)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    }

    fun doubleClick(screenX: Int, screenY: Int) = runOffEdt {
        robot.mouseMove(screenX, screenY)
        repeat(DOUBLE_CLICK_COUNT) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    fun longClick(screenX: Int, screenY: Int, holdFor: Duration = DEFAULT_LONG_CLICK_DURATION) =
        runOffEdt {
            robot.mouseMove(screenX, screenY)
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            try {
                Thread.sleep(holdFor.inWholeMilliseconds)
            } finally {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            }
        }

    fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        steps: Int = DEFAULT_SWIPE_STEPS,
        duration: Duration = DEFAULT_SWIPE_DURATION,
    ) = runOffEdt {
        val points = interpolateSwipePoints(startX, startY, endX, endY, steps)
        val pausePerStepMs = swipePauseMillis(duration, steps, autoDelayMs = robot.autoDelayMs)
        val firstPoint = points.first()
        robot.mouseMove(firstPoint.x, firstPoint.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        try {
            for (point in points.drop(1)) {
                robot.mouseMove(point.x, point.y)
                if (pausePerStepMs > 0) {
                    Thread.sleep(pausePerStepMs)
                }
            }
        } finally {
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
    }

    fun typeText(text: String) {
        val previousContents = runCatching { clipboard.getContents() }.getOrNull()
        try {
            clipboard.setContents(StringSelection(text))
            // OS pasteboard writes (notably macOS NSPasteboard) are asynchronous: setContents
            // returns immediately, but readers can observe the previous value for a short
            // window. If we dispatch Cmd+V before the pasteboard surfaces our text, the focused
            // field's paste handler reads stale (often empty) contents and the typed characters
            // never land. Poll the clipboard until it reports our string, with a bounded budget
            // so a misbehaving clipboard adapter cannot wedge the call indefinitely. Skip the
            // poll for adapters that don't support read-back (the headless no-op clipboard) so
            // that path doesn't burn the full settle timeout for nothing.
            if (clipboard.supportsRead) {
                awaitClipboardContents(text, CLIPBOARD_SETTLE_TIMEOUT_MS, CLIPBOARD_SETTLE_POLL_MS)
            }
            runOffEdt {
                val modifier = shortcutModifierKeyCode(detectMacOs())
                robot.keyPress(modifier)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(modifier)
            }
            if (!SwingUtilities.isEventDispatchThread() && clipboard.supportsRead) {
                // Drain queued AWT events (KEY_PRESSED/RELEASED + Compose's input pipeline) so
                // the paste handler has a chance to read the clipboard before we restore it.
                // Without this, the finally block can clobber the clipboard mid-paste and the
                // field receives empty text — the exact symptom that flaked the live validation.
                // One waitForIdle pump is not enough: Compose's paste action runs on its own
                // coroutine dispatcher which posts back to the EDT, so we pump a few times and
                // also wait a short interval to let the OS-side paste read complete before we
                // clobber the clipboard contents. Skip the whole block for adapters that don't
                // support clipboard read-back — there's no real paste handler to drain, so the
                // sleep would just add fixed latency to every headless typeText call.
                repeat(POST_PASTE_EDT_PUMPS) { robot.waitForIdle() }
                try {
                    Thread.sleep(POST_PASTE_SETTLE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                repeat(POST_PASTE_EDT_PUMPS) { robot.waitForIdle() }
            }
        } finally {
            if (previousContents != null) {
                runCatching { clipboard.setContents(previousContents) }
            }
        }
    }

    private fun awaitClipboardContents(expected: String, timeoutMs: Long, pollMs: Long) {
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
            try {
                Thread.sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    fun clearAndTypeText(text: String) {
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
    fun scrollWheel(screenX: Int, screenY: Int, wheelClicks: Int) = runOffEdt {
        robot.mouseMove(screenX, screenY)
        robot.mouseWheel(wheelClicks)
    }

    fun pressKey(keyCode: Int, modifiers: Int = 0) = runOffEdt {
        val modifierKeys = modifierMaskToKeyCodes(modifiers)
        for (mod in modifierKeys) robot.keyPress(mod)
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
        for (mod in modifierKeys.reversed()) robot.keyRelease(mod)
    }

    fun screenshot(region: Rectangle? = null): BufferedImage {
        val captureRegion = region ?: virtualDesktopBounds()
        return robot.createScreenCapture(captureRegion)
    }

    private fun runOffEdt(block: () -> Unit) = runOffEdt(robot, block)

    companion object {

        /**
         * Returns a [RobotDriver] that performs no real OS input or capture.
         *
         * Intended for tests and headless CI environments where constructing a real
         * `java.awt.Robot` (or touching the system clipboard / screen) is unavailable. Mouse and
         * key calls are silently dropped, screenshots return a 1×1 empty image, and clipboard
         * access is a no-op. Combine with the testing module's `ComposeAutomatorRule` /
         * `ComposeAutomatorExtension` to exercise the rule/extension lifecycle without an EDT.
         */
        fun headless(): RobotDriver = RobotDriver(NoopRobotAdapter, NoopClipboardAdapter)
    }
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
     * `true` when [getContents] reads back values written by [setContents]. The headless / no-op
     * adapter returns `false` so callers can skip clipboard-settle polling that would otherwise
     * spin its full timeout against a permanently-null reader.
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

private object NoopRobotAdapter : RobotAdapter {

    override val autoDelayMs: Int = 0

    // No-op input never blocks the EDT, so the worker-thread hop is unnecessary.
    override val requiresOffEdt: Boolean = false

    override fun mouseMove(x: Int, y: Int) = Unit

    override fun mousePress(buttons: Int) = Unit

    override fun mouseRelease(buttons: Int) = Unit

    override fun keyPress(keyCode: Int) = Unit

    override fun keyRelease(keyCode: Int) = Unit

    override fun mouseWheel(wheelClicks: Int) = Unit

    // Always return a fresh 1×1 image regardless of region size. RobotDriver.headless() is
    // documented as no-OS-contact (so we don't probe screen devices) and screenshot returns
    // are conceptually owned by the caller (BufferedImage is mutable; setRGB/createGraphics
    // are normal post-screenshot operations), so a per-call instance is required for safety.
    override fun createScreenCapture(region: Rectangle): BufferedImage =
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)

    override fun waitForIdle() = Unit
}

private object NoopClipboardAdapter : ClipboardAdapter {

    override val supportsRead: Boolean = false

    override fun getContents(): Transferable? = null

    override fun setContents(contents: Transferable) = Unit
}

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

private fun runOffEdt(robot: RobotAdapter, block: () -> Unit) {
    // Adapters that don't need the off-EDT marshal (synthetic, no-op) can run inline even
    // on the EDT — spawning a worker would deadlock the synthetic adapter, whose internal
    // `SwingUtilities.invokeAndWait` would wait forever for an EDT blocked on `Thread.join()`.
    if (!robot.requiresOffEdt || !SwingUtilities.isEventDispatchThread()) {
        block()
        return
    }
    var result: Result<Unit>? = null
    val thread = Thread({ result = runCatching(block) }, "spectre-robot")
    thread.start()
    thread.join()
    result!!.getOrThrow()
}

fun shortcutModifierKeyCode(isMacOs: Boolean): Int =
    if (isMacOs) KeyEvent.VK_META else KeyEvent.VK_CONTROL

fun modifierMaskToKeyCodes(mask: Int): List<Int> = buildList {
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

fun interpolateSwipePoints(
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

fun detectMacOs(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

private fun virtualDesktopBounds(): Rectangle {
    // GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices throws HeadlessException
    // when the JVM is running with -Djava.awt.headless=true (e.g. CI). RobotDriver.headless()
    // is documented as safe in that environment, so fall back to a 1×1 rectangle here so the
    // headless path never reaches a real device probe. The NoopRobotAdapter will still produce
    // an empty BufferedImage of that size.
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

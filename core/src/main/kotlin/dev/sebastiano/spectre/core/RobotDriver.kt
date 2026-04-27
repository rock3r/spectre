package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
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
            runOffEdt {
                val modifier = shortcutModifierKeyCode(detectMacOs())
                robot.keyPress(modifier)
                robot.keyPress(KeyEvent.VK_V)
                robot.keyRelease(KeyEvent.VK_V)
                robot.keyRelease(modifier)
            }
            if (!SwingUtilities.isEventDispatchThread()) {
                robot.waitForIdle()
            }
        } finally {
            if (previousContents != null) {
                runCatching { clipboard.setContents(previousContents) }
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
}

internal interface RobotAdapter {

    val autoDelayMs: Int

    fun mouseMove(x: Int, y: Int)

    fun mousePress(buttons: Int)

    fun mouseRelease(buttons: Int)

    fun keyPress(keyCode: Int)

    fun keyRelease(keyCode: Int)

    fun createScreenCapture(region: Rectangle): BufferedImage

    fun waitForIdle()
}

internal interface ClipboardAdapter {

    fun getContents(): Transferable?

    fun setContents(contents: Transferable)
}

private class AwtRobotAdapter(private val robot: Robot = createAwtRobot()) : RobotAdapter {

    override val autoDelayMs: Int
        get() = robot.autoDelay

    override fun mouseMove(x: Int, y: Int) = robot.mouseMove(x, y)

    override fun mousePress(buttons: Int) = robot.mousePress(buttons)

    override fun mouseRelease(buttons: Int) = robot.mouseRelease(buttons)

    override fun keyPress(keyCode: Int) = robot.keyPress(keyCode)

    override fun keyRelease(keyCode: Int) = robot.keyRelease(keyCode)

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        robot.createScreenCapture(region)

    override fun waitForIdle() = robot.waitForIdle()
}

private class SystemClipboardAdapter : ClipboardAdapter {
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

private fun runOffEdt(block: () -> Unit) {
    if (!SwingUtilities.isEventDispatchThread()) {
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

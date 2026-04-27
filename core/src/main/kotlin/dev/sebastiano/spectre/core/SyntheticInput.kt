package dev.sebastiano.spectre.core

import java.awt.Component
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * Returns a [RobotDriver] that delivers synthetic AWT events directly to the root window's AWT
 * hierarchy instead of routing through `java.awt.Robot`'s OS-level input.
 *
 * Useful for **Spectre's own validation** and for any test environment where:
 * - the host machine's mouse and keyboard must remain free for the developer
 * - tests run in parallel without contending for OS focus
 * - the recorder doesn't need to capture real screen pixels (synthetic screenshots paint the target
 *   component into a [BufferedImage] via `Component.paint(Graphics)`)
 *
 * The trade-off is that synthetic events bypass the OS HID layer:
 * - Compose's input pipeline (Skiko's AWT listeners) reads them identically to real Robot events,
 *   so click/type behaviour matches end-to-end
 * - OS-level shortcuts (Cmd+Tab, system menu access, etc.) are NOT exercised — those scenarios
 *   still need [RobotDriver]
 *
 * The driver discovers click targets by walking the root window plus all owned windows
 * ([Window.getOwnedWindows]) on every dispatch, so popups and secondary windows added after
 * construction work without re-creating the driver.
 *
 * Clipboard access is left as the system clipboard — paste-based [RobotDriver.typeText] still works
 * with synthetic Cmd/Ctrl+V keystrokes.
 */
fun RobotDriver.Companion.synthetic(rootWindow: Window): RobotDriver =
    RobotDriver(SyntheticRobotAdapter(rootWindow), SystemClipboardAdapter())

internal class SyntheticRobotAdapter(private val rootWindow: Window) : RobotAdapter {

    // Synthetic events have no OS-level inter-event delay; we always dispatch immediately.
    // The wait loop in ComposeAutomator handles synchronisation via waitForIdle / fingerprints.
    override val autoDelayMs: Int = 0

    @Volatile private var lastX: Int = 0
    @Volatile private var lastY: Int = 0
    @Volatile private var pressedButtons: Int = 0
    // Modifier-key state must be tracked across keyPress/keyRelease pairs and folded into the
    // `modifiers` field of every event we dispatch. The OS HID layer does this for us when running
    // through java.awt.Robot, but synthetic events bypass it — so without this, Cmd+V looks to
    // Compose's KeyEvent handler like a plain 'v' keystroke and paste never fires.
    @Volatile private var heldKeyModifiers: Int = 0

    // Press position + release history for click-vs-drag detection and click-count tracking.
    // Real AWT only fires MOUSE_CLICKED when press and release happen at the same location, and
    // increments clickCount on consecutive press/release pairs at the same spot within the
    // double-click interval. Without these, `swipe` would fire a spurious click at the swipe end
    // and `doubleClick` would emit two clickCount=1 events instead of clickCount=1 then 2.
    @Volatile private var pressX: Int = 0
    @Volatile private var pressY: Int = 0
    @Volatile private var lastClickX: Int = Int.MIN_VALUE
    @Volatile private var lastClickY: Int = Int.MIN_VALUE
    @Volatile private var lastClickTimeMs: Long = 0
    @Volatile private var lastClickCount: Int = 0

    override fun mouseMove(x: Int, y: Int) {
        lastX = x
        lastY = y
        // Dispatch a MOUSE_MOVED event so listeners that care (hover, drag tracking) see it.
        // We send DRAGGED instead of MOVED if any button is currently pressed, matching the
        // AWT contract for in-progress drags.
        val type = if (pressedButtons != 0) MouseEvent.MOUSE_DRAGGED else MouseEvent.MOUSE_MOVED
        dispatchMouse(type = type, button = NO_BUTTON, modifiers = mouseModifiers())
    }

    override fun mousePress(buttons: Int) {
        pressedButtons = pressedButtons or buttons
        pressX = lastX
        pressY = lastY
        dispatchMouse(
            type = MouseEvent.MOUSE_PRESSED,
            button = buttonNumber(buttons),
            modifiers = mouseModifiers(),
        )
    }

    override fun mouseRelease(buttons: Int) {
        // The release modifier mask is the buttons-still-pressed-after-release state, which
        // matches AWT's wire-protocol convention.
        val released = buttons
        pressedButtons = pressedButtons and released.inv()
        dispatchMouse(
            type = MouseEvent.MOUSE_RELEASED,
            button = buttonNumber(released),
            modifiers = mouseModifiers(),
        )
        // MOUSE_CLICKED only fires when the pointer didn't move between press and release —
        // otherwise this is a drag and AWT suppresses the click. Skipping the synthetic click
        // here is what stops `RobotDriver.swipe(...)` from triggering a spurious click at its
        // end target.
        if (lastX == pressX && lastY == pressY) {
            val now = System.currentTimeMillis()
            lastClickCount =
                if (
                    lastX == lastClickX &&
                        lastY == lastClickY &&
                        (now - lastClickTimeMs) < DOUBLE_CLICK_INTERVAL_MS
                ) {
                    lastClickCount + 1
                } else {
                    1
                }
            lastClickX = lastX
            lastClickY = lastY
            lastClickTimeMs = now
            dispatchMouse(
                type = MouseEvent.MOUSE_CLICKED,
                button = buttonNumber(released),
                modifiers = mouseModifiers(),
            )
        }
    }

    private fun mouseModifiers(): Int = pressedButtons or heldKeyModifiers

    override fun mouseWheel(wheelClicks: Int) {
        val window = findWindowAt(lastX, lastY) ?: return
        val target = findDeepestComponentAt(window, lastX, lastY) ?: window
        val origin = target.locationOnScreen
        // AWT's getUnitsToScroll() returns scrollAmount * wheelRotation. To keep that product
        // equal to the signed `wheelClicks` contract (and avoid the wheelClicks² blow-up that
        // both Codex and Bugbot flagged on the cb5d560 review), keep scrollAmount fixed at 1
        // unit per click and put the signed click count in wheelRotation.
        val event =
            MouseWheelEvent(
                target,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                mouseModifiers(),
                lastX - origin.x,
                lastY - origin.y,
                0, // clickCount
                false, // popupTrigger
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                1, // scrollAmount: one unit per click (matches OS wheel behaviour)
                wheelClicks, // wheelRotation: signed, positive away from the user
            )
        runOnEdt { target.dispatchEvent(event) }
    }

    override fun keyPress(keyCode: Int) {
        val modifierBit = modifierMaskFor(keyCode)
        if (modifierBit != 0) heldKeyModifiers = heldKeyModifiers or modifierBit
        dispatchKey(type = KeyEvent.KEY_PRESSED, keyCode = keyCode)
        // Real AWT emits KEY_TYPED after KEY_PRESSED for printable keys (with keyCode=UNDEFINED
        // and keyChar set to the produced character). Listeners that consume typed events
        // (search-as-you-type filters, character-counting validators) would miss input through
        // the synthetic driver without this — the real Robot path gets it for free via the OS.
        if (keyCode in PRINTABLE_KEY_CODES && (heldKeyModifiers and SHORTCUT_MODIFIER_MASK) == 0) {
            dispatchKey(
                type = KeyEvent.KEY_TYPED,
                keyCode = KeyEvent.VK_UNDEFINED,
                keyCharOverride = keyCharFor(keyCode),
            )
        }
    }

    override fun keyRelease(keyCode: Int) {
        dispatchKey(type = KeyEvent.KEY_RELEASED, keyCode = keyCode)
        val modifierBit = modifierMaskFor(keyCode)
        if (modifierBit != 0) heldKeyModifiers = heldKeyModifiers and modifierBit.inv()
    }

    /**
     * "Screen capture" without touching the OS — the target component paints itself into a
     * BufferedImage via Swing's `paint(Graphics)`. ComposePanel renders correctly through this path
     * because Skiko's AWT integration honours the standard paint contract.
     */
    override fun createScreenCapture(region: Rectangle): BufferedImage {
        val image =
            BufferedImage(
                region.width.coerceAtLeast(1),
                region.height.coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
        val target = findWindowAt(region.x, region.y) ?: rootWindow
        runOnEdt {
            val g = image.createGraphics()
            try {
                val origin = target.locationOnScreen
                g.translate(origin.x - region.x, origin.y - region.y)
                target.paint(g)
            } finally {
                g.dispose()
            }
        }
        return image
    }

    override fun waitForIdle() {
        // SwingUtilities.invokeAndWait flushes the EDT — equivalent to Robot.waitForIdle for
        // the synthetic event queue. We post a no-op event and wait for it to drain. If the
        // caller is already the EDT, the queue is being drained synchronously by definition.
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeAndWait {}
        }
    }

    private fun dispatchMouse(type: Int, button: Int, modifiers: Int) {
        val window = findWindowAt(lastX, lastY) ?: return
        val targetComponent = findDeepestComponentAt(window, lastX, lastY) ?: window
        val origin = targetComponent.locationOnScreen
        val localX = lastX - origin.x
        val localY = lastY - origin.y
        val event =
            MouseEvent(
                targetComponent,
                type,
                System.currentTimeMillis(),
                modifiers,
                localX,
                localY,
                if (type == MouseEvent.MOUSE_CLICKED) lastClickCount else 0,
                false, // popupTrigger
                button,
            )
        runOnEdt { targetComponent.dispatchEvent(event) }
    }

    private fun dispatchKey(type: Int, keyCode: Int, keyCharOverride: Char? = null) {
        // Key events go to the focus owner of whichever window owns the keyboard. Fall back to
        // the root window if no focus owner is present (no field focused yet).
        val focusOwner =
            allWindows().asSequence().mapNotNull { it.focusOwner }.firstOrNull() ?: rootWindow
        val event =
            KeyEvent(
                focusOwner,
                type,
                System.currentTimeMillis(),
                heldKeyModifiers,
                keyCode,
                keyCharOverride
                    ?: if (keyCode in PRINTABLE_KEY_CODES) keyCharFor(keyCode)
                    else KeyEvent.CHAR_UNDEFINED,
            )
        runOnEdt { focusOwner.dispatchEvent(event) }
    }

    private fun findWindowAt(screenX: Int, screenY: Int): Window? =
        // Iterate in reverse so popups and secondary windows (added after the root by the
        // pre-order walk in `collectWindowTree`) win the hit test against an overlapping parent.
        // Without this, a popup that sits on top of its owner would route synthetic input to the
        // owner — exactly the regression Codex flagged on the cb5d560 review.
        allWindows().asReversed().firstOrNull { window ->
            window.isVisible &&
                runCatching {
                        val origin = window.locationOnScreen
                        val size = window.size
                        screenX in origin.x..(origin.x + size.width) &&
                            screenY in origin.y..(origin.y + size.height)
                    }
                    .getOrDefault(false)
        }

    private fun allWindows(): List<Window> {
        val collected = mutableListOf<Window>()
        collectWindowTree(rootWindow, collected)
        return collected
    }
}

/**
 * Dispatches [block] on the AWT EDT, running inline if the caller is already on the EDT. Plain
 * `SwingUtilities.invokeAndWait` would deadlock here: `RobotDriver.runOffEdt` re-routes EDT callers
 * onto a worker thread, but that worker still needs the EDT to be free to drain its
 * `invokeAndWait`. Without this guard, calling `click()`/`typeText()`/`scrollWheel()` from a UI
 * callback hangs the automator hard.
 */
private fun runOnEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        SwingUtilities.invokeAndWait(block)
    }
}

private fun modifierMaskFor(keyCode: Int): Int =
    when (keyCode) {
        KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK
        KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK
        KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK
        KeyEvent.VK_META -> InputEvent.META_DOWN_MASK
        else -> 0
    }

private fun collectWindowTree(window: Window, into: MutableList<Window>) {
    into += window
    for (child in window.ownedWindows) {
        collectWindowTree(child, into)
    }
}

private fun findDeepestComponentAt(root: Component, screenX: Int, screenY: Int): Component? {
    val origin = runCatching { root.locationOnScreen }.getOrNull() ?: return null
    val localPoint = Point(screenX - origin.x, screenY - origin.y)
    val component = SwingUtilities.getDeepestComponentAt(root, localPoint.x, localPoint.y)
    return component ?: root
}

private fun buttonNumber(buttonMask: Int): Int =
    when {
        buttonMask and InputEvent.BUTTON1_DOWN_MASK != 0 -> MouseEvent.BUTTON1
        buttonMask and InputEvent.BUTTON2_DOWN_MASK != 0 -> MouseEvent.BUTTON2
        buttonMask and InputEvent.BUTTON3_DOWN_MASK != 0 -> MouseEvent.BUTTON3
        else -> MouseEvent.NOBUTTON
    }

private const val NO_BUTTON: Int = MouseEvent.NOBUTTON

// Conservative double-click window — matches the typical OS default. Two press/release pairs
// at the same coordinates within this window count as a double-click, so doubleClick() emits
// MOUSE_CLICKED with clickCount=2 on the second pair instead of two clickCount=1 events.
private const val DOUBLE_CLICK_INTERVAL_MS: Long = 500L

// Modifiers that mean "this isn't a typing keystroke" — Cmd/Ctrl/Alt mask off KEY_TYPED so that
// shortcut combos like Cmd+V don't accidentally inject a 'v' character into the focused field.
// Shift is intentionally NOT here: Shift+A is a real typed 'A'.
private val SHORTCUT_MODIFIER_MASK: Int =
    java.awt.event.InputEvent.META_DOWN_MASK or
        java.awt.event.InputEvent.CTRL_DOWN_MASK or
        java.awt.event.InputEvent.ALT_DOWN_MASK

private fun keyCharFor(keyCode: Int): Char =
    when (keyCode) {
        KeyEvent.VK_ENTER -> '\n'
        KeyEvent.VK_SPACE -> ' '
        in KeyEvent.VK_A..KeyEvent.VK_Z -> ('a' + (keyCode - KeyEvent.VK_A))
        in KeyEvent.VK_0..KeyEvent.VK_9 -> ('0' + (keyCode - KeyEvent.VK_0))
        else -> KeyEvent.CHAR_UNDEFINED
    }

// Conservative subset of printable VK_ codes for KeyEvent's keyChar; matches what RobotDriver
// typically dispatches via direct keyPress.
private val PRINTABLE_KEY_CODES: Set<Int> = buildSet {
    addAll((KeyEvent.VK_A..KeyEvent.VK_Z))
    addAll((KeyEvent.VK_0..KeyEvent.VK_9))
    add(KeyEvent.VK_SPACE)
    add(KeyEvent.VK_ENTER)
}

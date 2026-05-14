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
 * Clipboard access is left as the system clipboard — paste-based [RobotDriver.pasteText] still
 * works with synthetic Cmd/Ctrl+V keystrokes.
 */
internal class SyntheticRobotAdapter(private val rootWindow: Window) : RobotAdapter {

    // Synthetic events have no OS-level inter-event delay; we always dispatch immediately.
    // The wait loop in ComposeAutomator handles synchronisation via waitForIdle / fingerprints.
    override val autoDelayMs: Int = 0

    // The synthetic adapter's `dispatchMouse` / `dispatchKey` / `createScreenCapture` already
    // marshal to the EDT via `runOnEdt` when needed. RobotDriver's worker-thread hop is therefore
    // both unnecessary and harmful here: an EDT caller would block on `Thread.join()` while the
    // worker thread blocked on `SwingUtilities.invokeAndWait`, deadlocking the whole pipeline
    // (Codex P1 on PR #35).
    override val requiresOffEdt: Boolean = false

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
    // The mouse-grab target: real AWT routes every MOUSE_DRAGGED and the final MOUSE_RELEASED
    // to whichever component received MOUSE_PRESSED, regardless of where the cursor moves.
    // Without this, `RobotDriver.swipe(...)` would deliver each drag step to a different
    // component along the path, breaking listeners that expect the full drag stream.
    @Volatile private var grabTarget: Component? = null
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
        // Compute the click sequence count at press time so MOUSE_PRESSED, MOUSE_RELEASED, and
        // (if no drag) MOUSE_CLICKED all carry the same count. Real AWT fires the second press
        // of a double-click with `clickCount = 2`, so listeners that detect double-clicks from
        // press/release events (rather than CLICKED) need this too.
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
            dispatchMouse(
                type = MouseEvent.MOUSE_CLICKED,
                button = buttonNumber(released),
                modifiers = mouseModifiers(),
            )
        }
    }

    private fun mouseModifiers(): Int = pressedButtons or heldKeyModifiers

    private fun clickCountFor(eventType: Int): Int =
        when (eventType) {
            MouseEvent.MOUSE_PRESSED,
            MouseEvent.MOUSE_RELEASED,
            MouseEvent.MOUSE_CLICKED -> lastClickCount
            else -> 0
        }

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
            val shiftHeld = (heldKeyModifiers and InputEvent.SHIFT_DOWN_MASK) != 0
            dispatchKey(
                type = KeyEvent.KEY_TYPED,
                keyCode = KeyEvent.VK_UNDEFINED,
                keyCharOverride = keyCharFor(keyCode, shift = shiftHeld),
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
        val targetComponent =
            when (type) {
                // While a button is down (drag in progress) the press target retains the grab —
                // every DRAGGED and the final RELEASED/CLICKED route there even if the cursor
                // has moved over a different component.
                MouseEvent.MOUSE_DRAGGED,
                MouseEvent.MOUSE_RELEASED,
                MouseEvent.MOUSE_CLICKED -> grabTarget ?: hitTestComponent() ?: return
                MouseEvent.MOUSE_PRESSED -> {
                    val target = hitTestComponent() ?: return
                    grabTarget = target
                    target
                }
                else -> hitTestComponent() ?: return
            }
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
                clickCountFor(type),
                false, // popupTrigger
                button,
            )
        runOnEdt { targetComponent.dispatchEvent(event) }
        // Release the grab after the click sequence completes (or the drag ends without click).
        if (type == MouseEvent.MOUSE_RELEASED) grabTarget = null
    }

    private fun hitTestComponent(): Component? {
        val window = findWindowAt(lastX, lastY) ?: return null
        return findDeepestComponentAt(window, lastX, lastY) ?: window
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
                // Real OS-injected KEY_PRESSED/KEY_RELEASED events for printable keys carry
                // the produced char too. JavaDoc says the value is "not guaranteed to be
                // meaningful" outside of KEY_TYPED, but in practice it IS set, and Compose
                // Desktop's paste path on this version reads `keyChar` on KEY_PRESSED to
                // resolve Cmd/Ctrl+V — setting CHAR_UNDEFINED here makes synthetic paste flake.
                // Codex flagged this as a P2 fidelity issue but the empirical trade-off favours
                // the current behaviour. The override branch carries the Shift-aware char from
                // `keyPress`'s KEY_TYPED dispatch.
                keyCharOverride
                    ?: if (keyCode in PRINTABLE_KEY_CODES)
                        keyCharFor(
                            keyCode,
                            shift = (heldKeyModifiers and InputEvent.SHIFT_DOWN_MASK) != 0,
                        )
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
                        // Half-open range matches AWT's `Rectangle.contains` semantics so a
                        // point at `origin + size` falls outside, not inside — touching/adjacent
                        // popups and secondary windows that share an edge get routed to the
                        // right target.
                        screenX >= origin.x &&
                            screenX < origin.x + size.width &&
                            screenY >= origin.y &&
                            screenY < origin.y + size.height
                    }
                    .getOrDefault(false)
        }

    private fun allWindows(): List<Window> {
        val collected = LinkedHashSet<Window>()
        collectWindowTree(rootWindow, collected)
        // Walk every other top-level window (and its owned tree). The visibility check is
        // applied per-window inside `collectWindowTree` rather than at the top level: Swing's
        // `SharedOwnerFrame` (parent of `JDialog(null, …)`) is itself a top-level window that
        // is NOT showing, but its owned dialogs may be — so filtering hidden top-levels out
        // here would drop those dialogs and synthetic input would silently miss them, even
        // though `WindowTracker` correctly surfaces them.
        for (other in Window.getWindows()) {
            if (other.owner == null && other !== rootWindow) {
                collectWindowTree(other, collected)
            }
        }
        return collected.toList()
    }
}

/**
 * Dispatches [block] on the AWT EDT, running inline if the caller is already on the EDT.
 *
 * Compose Desktop's pointer/measure/layout pipeline requires events to be processed on the EDT —
 * `performMeasureAndLayout called during measure layout` errors and dropped events surface
 * immediately when this runs from another thread, so we cannot bypass the marshal.
 *
 * The earlier deadlock (Codex P1 on PR #35) — where a caller routed through `RobotDriver.runOffEdt`
 * from an EDT thread had its worker block on `invokeAndWait` while the EDT itself was blocked on
 * `Thread.join()` — is fixed at the `RobotDriver` layer: [SyntheticRobotAdapter.requiresOffEdt] is
 * `false`, so the worker hop is skipped and `runOnEdt` runs the block directly when the caller is
 * already the EDT.
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

private fun collectWindowTree(window: Window, into: MutableCollection<Window>) {
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

internal fun keyCharFor(keyCode: Int, shift: Boolean = false): Char =
    when (keyCode) {
        KeyEvent.VK_ENTER -> '\n'
        KeyEvent.VK_SPACE -> ' '
        in KeyEvent.VK_A..KeyEvent.VK_Z ->
            // Shift produces uppercase letters — matches what the OS HID layer would inject
            // when KEY_TYPED reaches a focused text field.
            if (shift) ('A' + (keyCode - KeyEvent.VK_A)) else ('a' + (keyCode - KeyEvent.VK_A))
        in KeyEvent.VK_0..KeyEvent.VK_9 ->
            // US-layout shifted digit symbols. Tests can rely on '!' for Shift+1, etc., so
            // listeners that consume KEY_TYPED character validators see the right input.
            if (shift) SHIFTED_DIGIT_CHARS[keyCode - KeyEvent.VK_0]
            else ('0' + (keyCode - KeyEvent.VK_0))
        else ->
            if (shift) SHIFTED_PUNCTUATION_CHARS[keyCode] ?: KeyEvent.CHAR_UNDEFINED
            else PUNCTUATION_CHARS[keyCode] ?: KeyEvent.CHAR_UNDEFINED
    }

private val SHIFTED_DIGIT_CHARS: CharArray =
    charArrayOf(')', '!', '@', '#', '$', '%', '^', '&', '*', '(')

private val PUNCTUATION_CHARS: Map<Int, Char> =
    mapOf(
        KeyEvent.VK_MINUS to '-',
        KeyEvent.VK_EQUALS to '=',
        KeyEvent.VK_OPEN_BRACKET to '[',
        KeyEvent.VK_CLOSE_BRACKET to ']',
        KeyEvent.VK_BACK_SLASH to '\\',
        KeyEvent.VK_SEMICOLON to ';',
        KeyEvent.VK_QUOTE to '\'',
        KeyEvent.VK_COMMA to ',',
        KeyEvent.VK_PERIOD to '.',
        KeyEvent.VK_SLASH to '/',
        KeyEvent.VK_BACK_QUOTE to '`',
    )

private val SHIFTED_PUNCTUATION_CHARS: Map<Int, Char> =
    mapOf(
        KeyEvent.VK_MINUS to '_',
        KeyEvent.VK_EQUALS to '+',
        KeyEvent.VK_OPEN_BRACKET to '{',
        KeyEvent.VK_CLOSE_BRACKET to '}',
        KeyEvent.VK_BACK_SLASH to '|',
        KeyEvent.VK_SEMICOLON to ':',
        KeyEvent.VK_QUOTE to '"',
        KeyEvent.VK_COMMA to '<',
        KeyEvent.VK_PERIOD to '>',
        KeyEvent.VK_SLASH to '?',
        KeyEvent.VK_BACK_QUOTE to '~',
    )

// Conservative subset of printable VK_ codes for KeyEvent's keyChar; matches what RobotDriver
// dispatches via direct keyPress for key-event text entry.
private val PRINTABLE_KEY_CODES: Set<Int> = buildSet {
    addAll((KeyEvent.VK_A..KeyEvent.VK_Z))
    addAll((KeyEvent.VK_0..KeyEvent.VK_9))
    addAll(PUNCTUATION_CHARS.keys)
    add(KeyEvent.VK_SPACE)
    add(KeyEvent.VK_ENTER)
}

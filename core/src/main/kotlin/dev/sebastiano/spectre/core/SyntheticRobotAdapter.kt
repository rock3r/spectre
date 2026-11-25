package dev.sebastiano.spectre.core

import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Returns a [RobotDriver] that delivers synthetic AWT events directly to the root window's AWT
 * hierarchy instead of routing through `java.awt.Robot`'s OS-level input.
 *
 * Useful for **Spectre's own validation** and for any test environment where:
 * - the host machine's mouse and keyboard must remain free for the developer
 * - tests run in parallel without contending for OS focus
 * - real mouse/keyboard focus must stay out of the test process
 *
 * The trade-off is that synthetic events bypass the OS HID layer:
 * - Compose's input pipeline (Skiko's AWT listeners) reads them identically to real Robot events,
 *   so click/type behaviour matches end-to-end
 * - OS-level shortcuts (Cmd+Tab, system menu access, etc.) are NOT exercised — those scenarios
 *   still need [RobotDriver]
 *
 * The driver discovers click targets by walking [rootWindow] (when pinned) plus every other
 * top-level window and its owned tree ([Window.getWindows] / [Window.getOwnedWindows]) on every
 * dispatch, so popups and secondary windows added after construction work without re-creating the
 * driver. Where windows overlap, the newer one wins, and while a modal dialog is showing, the
 * windows it blocks get no synthetic input, as with real input. A null [rootWindow] is the
 * no-window factory used by `RobotDriver.synthetic()`: hit testing still walks the live AWT
 * hierarchy. Key events require either an eligible AWT focus owner or a showing Compose surface
 * whose semantics tree contains the focused node.
 *
 * Clipboard access is left as the system clipboard — paste-based [RobotDriver.pasteText] still
 * works with synthetic Cmd/Ctrl+V keystrokes.
 */
internal class SyntheticRobotAdapter(private val rootWindow: Window? = null) : RobotAdapter {

    // Synthetic events have no OS-level inter-event delay; we always dispatch immediately.
    // The wait loop in ComposeAutomator handles synchronisation via waitForIdle / fingerprints.
    override val autoDelayMs: Int = 0

    // The synthetic adapter's `dispatchMouse` / `dispatchKey` already marshal to the EDT via
    // `runOnEdt` when needed. RobotDriver's worker-thread hop is therefore both unnecessary and
    // harmful here: an EDT caller would block on `Thread.join()` while the worker thread blocked on
    // `SwingUtilities.invokeAndWait`, deadlocking the whole pipeline (Codex P1 on PR #35).
    override val requiresOffEdt: Boolean = false

    override val shouldDrainAfterClipboardPaste: Boolean
        get() = !SwingUtilities.isEventDispatchThread()

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
    // AWT sends MOUSE_CLICKED to the component that received the press and release. If the
    // release opened a modal dialog, that component is now blocked and the click is dropped.
    @Volatile private var releaseTarget: Component? = null
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
        val previousPressedButtons = pressedButtons
        val previousLastClickX = lastClickX
        val previousLastClickY = lastClickY
        val previousLastClickTimeMs = lastClickTimeMs
        val previousLastClickCount = lastClickCount
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
        var acquired = false
        try {
            dispatchMouse(
                type = MouseEvent.MOUSE_PRESSED,
                button = buttonNumber(buttons),
                modifiers = mouseModifiers(),
            )
            acquired = true
        } finally {
            if (!acquired) {
                pressedButtons = previousPressedButtons
                grabTarget = null
                releaseTarget = null
                lastClickX = previousLastClickX
                lastClickY = previousLastClickY
                lastClickTimeMs = previousLastClickTimeMs
                lastClickCount = previousLastClickCount
            }
        }
    }

    override fun mouseRelease(buttons: Int) {
        // The release modifier mask is the buttons-still-pressed-after-release state, which
        // matches AWT's wire-protocol convention.
        val released = buttons
        pressedButtons = pressedButtons and released.inv()
        try {
            dispatchMouse(
                type = MouseEvent.MOUSE_RELEASED,
                button = buttonNumber(released),
                modifiers = mouseModifiers(),
            )
            // MOUSE_CLICKED only fires when the pointer didn't move between press and release.
            if (lastX == pressX && lastY == pressY) {
                dispatchMouse(
                    type = MouseEvent.MOUSE_CLICKED,
                    button = buttonNumber(released),
                    modifiers = mouseModifiers(),
                )
            }
        } finally {
            grabTarget = null
            releaseTarget = null
        }
    }

    private fun mouseModifiers(): Int = pressedButtons or heldKeyModifiers

    /**
     * The component a mouse event of [type] goes to, updating the press grab and release target.
     *
     * While a button is down (drag in progress) the press target retains the grab — every DRAGGED
     * and the final RELEASED route there even if the cursor has moved over a different component.
     * MOUSE_CLICKED goes to the component that received the release.
     */
    private fun mouseTarget(type: Int): Component? =
        when (type) {
            MouseEvent.MOUSE_DRAGGED -> grabTarget ?: hitTestComponent()
            MouseEvent.MOUSE_RELEASED -> {
                val target = grabTarget ?: hitTestComponent()
                grabTarget = null
                releaseTarget = target
                target
            }
            MouseEvent.MOUSE_CLICKED -> releaseTarget
            MouseEvent.MOUSE_PRESSED -> hitTestComponent().also { grabTarget = it }
            else -> hitTestComponent()
        }

    override fun mouseWheel(wheelClicks: Int) {
        runOnEdt {
            val window = findWindowAt(lastX, lastY) ?: undeliverablePointer("mouse wheel")
            val target = findDeepestComponentAt(window, lastX, lastY) ?: window
            check(target.canReceiveMouseInput()) {
                "Cannot deliver synthetic mouse wheel input: the intended recipient is not " +
                    "showing or is modal-blocked."
            }
            val origin = target.locationOnScreen
            val event =
                MouseWheelEvent(
                    target,
                    MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    mouseModifiers(),
                    lastX - origin.x,
                    lastY - origin.y,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    wheelClicks,
                )
            target.dispatchEvent(event)
        }
    }

    override fun keyPress(keyCode: Int) {
        val modifierBit = modifierMaskFor(keyCode)
        if (modifierBit != 0) heldKeyModifiers = heldKeyModifiers or modifierBit
        var delivered = false
        try {
            dispatchKey(type = KeyEvent.KEY_PRESSED, keyCode = keyCode)
            delivered = true
        } finally {
            if (!delivered && modifierBit != 0) {
                heldKeyModifiers = heldKeyModifiers and modifierBit.inv()
            }
        }
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
        val modifierBit = modifierMaskFor(keyCode)
        try {
            dispatchKey(type = KeyEvent.KEY_RELEASED, keyCode = keyCode)
        } finally {
            if (modifierBit != 0) heldKeyModifiers = heldKeyModifiers and modifierBit.inv()
        }
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
        runOnEdt {
            // A successful press owns the rest of its gesture, and a successful release owns the
            // trailing CLICKED event. A handler may hide its window or open a modal while that
            // event is being dispatched; real AWT then drops the remaining event(s) rather than
            // reporting that the already-delivered gesture failed. Capture this before
            // mouseTarget advances the press/release state.
            val continuesDeliveredGesture =
                when (type) {
                    MouseEvent.MOUSE_RELEASED -> grabTarget != null
                    MouseEvent.MOUSE_CLICKED -> releaseTarget != null
                    else -> false
                }
            val targetComponent = mouseTarget(type) ?: undeliverablePointer(mouseAction(type))
            if (continuesDeliveredGesture && !targetComponent.canReceiveMouseInput()) {
                return@runOnEdt
            }
            check(targetComponent.canReceiveMouseInput()) {
                "Cannot deliver synthetic ${mouseAction(type)} input: the intended recipient is " +
                    "not showing or is modal-blocked."
            }
            val origin = targetComponent.locationOnScreen
            val event =
                MouseEvent(
                    targetComponent,
                    type,
                    System.currentTimeMillis(),
                    modifiers,
                    lastX - origin.x,
                    lastY - origin.y,
                    clickCountFor(type, lastClickCount),
                    false,
                    button,
                )
            targetComponent.dispatchEvent(event)
            if (type == MouseEvent.MOUSE_PRESSED) targetComponent.focusComposeCanvasOnPress()
        }
    }

    private fun hitTestComponent(): Component? {
        val window = findWindowAt(lastX, lastY) ?: return null
        return findDeepestComponentAt(window, lastX, lastY) ?: window
    }

    private fun dispatchKey(type: Int, keyCode: Int, keyCharOverride: Char? = null) {
        val modifiers = heldKeyModifiers
        // Real OS-injected KEY_PRESSED/KEY_RELEASED events for printable keys carry the produced
        // char too. JavaDoc says the value is "not guaranteed to be meaningful" outside of
        // KEY_TYPED, but in practice it IS set, and Compose Desktop's paste path on this version
        // reads `keyChar` on KEY_PRESSED to resolve Cmd/Ctrl+V — setting CHAR_UNDEFINED here makes
        // synthetic paste flake. Codex flagged this as a P2 fidelity issue but the empirical
        // trade-off favours the current behaviour. The override branch carries the Shift-aware
        // char from `keyPress`'s KEY_TYPED dispatch.
        val keyChar =
            keyCharOverride
                ?: if (keyCode in PRINTABLE_KEY_CODES)
                    keyCharFor(keyCode, shift = (modifiers and InputEvent.SHIFT_DOWN_MASK) != 0)
                else KeyEvent.CHAR_UNDEFINED
        // Resolved on the EDT: the lookup reads Swing focus state and Compose semantics.
        runOnEdt {
            val target = resolveKeyTarget()
            val event =
                KeyEvent(target, type, System.currentTimeMillis(), modifiers, keyCode, keyChar)
            // Bypass `KeyboardFocusManager` via `redispatchEvent`.
            // `Component.dispatchEvent(KeyEvent)` would otherwise hand the event to KFM first,
            // which redirects to the global focus owner — null under `apple.awt.UIElement=true`,
            // causing the event to be silently dropped. The `redispatchEvent` entry point exists
            // precisely for callers "using key events generated from non-keyboard sources": it
            // sets `KeyEvent.focusManagerIsDispatching=true` so AWT skips the KFM redirect and
            // delivers straight to the target's `processKeyEvent`.
            KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(target, event)
        }
    }

    private fun resolveKeyTarget(): Component {
        // Pick the AWT component that should receive the synthetic KeyEvent. The chain below is
        // ordered from "AWT thinks it knows where focus is" down to "best-effort by structure".
        //
        // Why this is more involved than "dispatch to the focus owner": when macOS runs the JVM as
        // an `apple.awt.UIElement`, AWT never grants OS keyboard focus to any window — every
        // `Window.focusOwner` stays null even though Compose's own focus model has a focused
        // composable. Without a fallback, real text input never reaches Compose at all.
        //
        // Compose Desktop registers its AWT `KeyListener` on
        // `ComposeSceneMediator.contentComponent` — the inner Skiko canvas (a subclass of
        // `org.jetbrains.skiko.SkiaLayer` or `org.jetbrains.skiko.swing.SkiaSwingLayer`) inside
        // the Compose host panel, NOT on the panel itself. So dispatching to the panel produces
        // visible focus side-effects but no text. We pick the deepest descendant of a Compose
        // host that actually has a registered `KeyListener`, which lands on the Skiko canvas in
        // real Compose hierarchies and works for any plain-Swing fixture (test or otherwise) that
        // registers its own key listener.
        val windows = allWindows()
        return windows
            .asSequence()
            .mapNotNull { it.focusOwner }
            .firstOrNull { it.canReceiveKeyInput() }
            ?: findComposeKeyEventTarget(windows)
                ?.takeIf { it.canReceiveKeyInput() }
                ?.findKeyListenerSelfOrDescendant()
            ?: error(
                "RobotDriver.synthetic() has no showing, enabled, focused key recipient to " +
                    "deliver key events to. Focus a Swing component or key-listening Compose " +
                    "surface first."
            )
    }

    private fun findWindowAt(screenX: Int, screenY: Int): Window? =
        // Iterate in reverse so popups and secondary windows (added after the root by the
        // pre-order walk in `collectWindowTree`) win the hit test against an overlapping parent.
        // Without this, a popup that sits on top of its owner would route synthetic input to the
        // owner — exactly the regression Codex flagged on the cb5d560 review.
        allWindows().asReversed().firstOrNull { window ->
            window.isShowing &&
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
        val windows = syntheticInputWindows()
        return if (rootWindow == null || rootWindow in windows) windows else windows + rootWindow
    }
}

private fun undeliverablePointer(action: String): Nothing =
    error(
        "Cannot deliver synthetic $action input: no showing, unblocked AWT recipient exists at " +
            "the requested pointer location."
    )

private fun mouseAction(type: Int): String =
    when (type) {
        MouseEvent.MOUSE_MOVED -> "pointer move"
        MouseEvent.MOUSE_DRAGGED -> "pointer drag"
        MouseEvent.MOUSE_PRESSED -> "mouse press"
        MouseEvent.MOUSE_RELEASED -> "mouse release"
        MouseEvent.MOUSE_CLICKED -> "mouse click"
        else -> "mouse event"
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
 *
 * Off the EDT this waits like [SwingUtilities.invokeAndWait], with one exception: when [block]
 * opens a modal dialog (a click or key handler calling `JDialog.setVisible(true)` or IntelliJ's
 * `DialogWrapper.show()`), AWT runs a nested event loop inside `dispatchEvent` and [block] does not
 * return until the dialog closes. Waiting for it would hang the caller, which then could never
 * drive the dialog. A marker event posted right after [block] tells the two cases apart: the EDT
 * only reaches the marker before [block] returns if it is pumping events in a nested loop, and then
 * this returns while [block] stays parked in that loop. Exceptions [block] throws before returning
 * are rethrown wrapped in [InvocationTargetException], as `invokeAndWait` does.
 */
private fun runOnEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
        return
    }
    val finished = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    SwingUtilities.invokeLater {
        failure.set(runCatching(block).exceptionOrNull())
        finished.countDown()
    }
    val markerReached = CountDownLatch(1)
    SwingUtilities.invokeLater { markerReached.countDown() }
    markerReached.await()
    if (finished.count == 0L) {
        failure.get()?.let { throw InvocationTargetException(it) }
    }
}

private fun clickCountFor(eventType: Int, clickCount: Int): Int =
    when (eventType) {
        MouseEvent.MOUSE_PRESSED,
        MouseEvent.MOUSE_RELEASED,
        MouseEvent.MOUSE_CLICKED -> clickCount
        else -> 0
    }

private fun modifierMaskFor(keyCode: Int): Int =
    when (keyCode) {
        KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK
        KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK
        KeyEvent.VK_ALT -> InputEvent.ALT_DOWN_MASK
        KeyEvent.VK_META -> InputEvent.META_DOWN_MASK
        else -> 0
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

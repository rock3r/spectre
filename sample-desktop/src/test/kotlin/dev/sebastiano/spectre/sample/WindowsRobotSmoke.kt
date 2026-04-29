@file:JvmName("WindowsRobotSmoke")

package dev.sebastiano.spectre.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.composeBoundsToAwtCenter
import java.awt.Dimension
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual smoke for [RobotDriver] on Windows. Run via `./gradlew
 * :sample-desktop:runWindowsRobotSmoke` — opens a Compose window with a counter button, a text
 * field, and a shortcut handler, then drives them through a real `java.awt.Robot` (not the
 * synthetic AWT-event-only `RobotDriver.synthetic`). Each scenario reports PASS/FAIL.
 *
 * Issue #20 checklist coverage:
 * - mouseMove + mousePress against the spawned window (counter button)
 * - pressKey single character — focus baseline so a typeText failure isolates from focus issues
 * - clipboard-driven typeText into a Compose `BasicTextField`
 * - clearAndTypeText (Ctrl+A on Windows, Cmd+A on macOS — uses the platform-aware
 *   `shortcutModifierKeyCode`)
 * - pressKey with Ctrl+S — verifies the modifier-mask → keyCode path Spectre uses for shortcuts
 *
 * Findings from running this on Windows 11 + JBR 21:
 *
 * 1. **Foreground-stealing prevention requires `setAlwaysOnTop(true)`.** When the JVM is spawned by
 *    another foreground process (Gradle, IntelliJ), Windows refuses to bring its JFrame to the
 *    front via `toFront()` alone. Without `isAlwaysOnTop=true` the Compose window renders but stays
 *    behind the spawning terminal; `Robot.mousePress` then lands on the terminal pixels. This is a
 *    Windows/AWT behaviour, not a Spectre bug — the production `ComposeAutomator` API doesn't fight
 *    it because validation tests use synthetic input that bypasses OS-level z-order. Anyone wiring
 *    a real-Robot Windows test must mirror the `isAlwaysOnTop` pattern from this smoke or wrap the
 *    SUT in an alwaysOnTop fixture.
 * 2. **Wrap modifiers (size, padding, background) belong on the BasicTextField itself, not a
 *    surrounding Box.** A 48dp Box around a single-line BasicTextField creates ~24dp of empty space
 *    below the text input; a Robot click aimed at the Box's centre lands in that empty space and
 *    does not focus the field, leaving subsequent typeText with nothing to type into. This is a
 *    generic Compose layout fact rather than a Windows quirk, but the failure mode looks identical
 *    to a focus bug, so it's worth banking explicitly.
 * 3. **Cold-JVM warmup click.** The very first Robot mouse event after a freshly-shown alwaysOnTop
 *    JFrame is empirically dropped on Windows ~half the time — observed flake where scenario 1's
 *    click registers as 0 → 0 but scenario 2's double-click registers as 0 → 2 on the same button.
 *    The smoke fires one sacrificial warmup click before scenarios run; the production
 *    `ComposeAutomator` API doesn't see this because validation tests use synthetic input. Real-
 *    Robot Windows fixtures should mirror the warmup pattern.
 *
 * What this *doesn't* exercise:
 * - Cross-monitor `mouseMove` to a different DPI scale — covered by `WindowsHiDpiScenariosTest`.
 * - Deliberately-stolen-focus scenario — `isAlwaysOnTop` keeps the smoke window foreground so the
 *   test exercises the focused-input path. Driving Robot at a non-foreground Compose window on
 *   Windows is not currently validated; document any future need under #20 if it arises.
 * - Jewel/IntelliJ tool-window text fields — that's `:sample-intellij-plugin`'s territory and gates
 *   on the macOS-only `ide-uitest.yml` workflow today.
 *
 * Mirrors the shape of `WindowsHiDpiDiagnostic` and `FfmpegGdigrabSmoke` so future Windows platform
 * smokes can be added alongside.
 */
fun main() {
    var exitCode = 0
    try {
        exitCode = runSmoke()
    } catch (t: Throwable) {
        System.err.println("Smoke crashed: ${t.message}\n${t.stackTraceToString()}")
        exitCode = 2
    } finally {
        exitProcess(exitCode)
    }
}

private fun runSmoke(): Int {
    val state = SmokeState()
    val frameRef = AtomicReference<JFrame>()
    SwingUtilities.invokeLater {
        val composePanel =
            ComposePanel().apply {
                preferredSize = Dimension(PANEL_WIDTH, PANEL_HEIGHT)
                setContent { SmokeContent(state) }
            }
        val frame =
            JFrame("Spectre Robot smoke").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = composePanel
                pack()
                setLocationRelativeTo(null)
                // Windows enforces foreground-stealing prevention — a process spawned by another
                // process (Gradle, IntelliJ) cannot bring its window to the foreground via
                // toFront() alone. AlwaysOnTop bypasses that, putting the JFrame above the
                // Gradle terminal so Robot mouse events actually land on Compose pixels rather
                // than on whatever was foreground when the JVM started.
                isAlwaysOnTop = true
                isVisible = true
                toFront()
                requestFocus()
            }
        state.frame = frame
        state.composePanel = composePanel
        frameRef.set(frame)
    }

    // Block until the window is up AND the first composition has produced non-zero
    // boundsInWindow for the targets we'll click. A fixed sleep is unreliable on cold JVMs:
    // Compose Desktop's first render can take >200ms, AWT shows the window asynchronously,
    // and `Rect.Zero` bounds would silently route Robot clicks at (0,0) — out of the panel.
    // Poll until both target rects are laid out, with a bounded timeout so a wedged composition
    // surfaces a clear error rather than hanging forever.
    waitForFrame(frameRef)
    waitForLayout(state)
    // Layout being non-zero means Compose has done its first measure/layout pass, but the AWT
    // mouse-input handlers attach a few frames later — empirically the first Robot click can
    // arrive before they're wired and gets dropped. A short warmup after layout closes that
    // race without wasting time on cold-JVM boots that took longer to lay out anyway.
    Thread.sleep(POST_LAYOUT_WARMUP_MS)

    printEnvironment(state)

    val driver = RobotDriver()
    // Cold-JVM warmup: the very first Robot mouse event after a freshly-shown alwaysOnTop
    // JFrame is empirically dropped on Windows ~half the time — observed flake where
    // scenario 1's click registers as 0 → 0 but scenario 2's double-click registers as
    // 0 → 2 on the same button. Fire one sacrificial click outside the assertion-bearing
    // scenarios so the production scenarios run on a warm input pipeline.
    warmupRobot(driver, state)
    val results = mutableListOf<ScenarioResult>()

    // Order matters: typeText asserts exact equality on a freshly-empty field, so it has to
    // run before any keystroke that would have left state in the BasicTextField. After that,
    // pressKey appends one character (verifying focus + raw keystrokes), and clearAndTypeText
    // wipes and re-types (verifying the Ctrl+A + Backspace path).
    results += scenarioCounterClick(driver, state)
    results += scenarioCounterDoubleClick(driver, state)
    results += scenarioTypeText(driver, state)
    results += scenarioPressKeySingleChar(driver, state)
    results += scenarioClearAndTypeText(driver, state)
    results += scenarioCtrlSShortcut(driver, state)

    println()
    println("--- WindowsRobotSmoke results ---")
    results.forEach {
        println("  ${it.label}: ${if (it.passed) "PASS" else "FAIL"} — ${it.detail}")
    }
    val passed = results.count { it.passed }
    val total = results.size
    println("--- $passed/$total passed ---")

    SwingUtilities.invokeLater {
        state.frame?.isVisible = false
        state.frame?.dispose()
    }

    return if (passed == total) 0 else 1
}

private fun printEnvironment(state: SmokeState) {
    val frame = state.frame
    val panel = state.composePanel
    println("--- WindowsRobotSmoke environment ---")
    println(
        "os.name=\"${System.getProperty("os.name")}\" os.version=\"${System.getProperty("os.version")}\""
    )
    if (frame != null) {
        val gc = frame.graphicsConfiguration
        println(
            "frame.bounds=${frame.bounds} isFocused=${frame.isFocused} " +
                "isAlwaysOnTop=${frame.isAlwaysOnTop} isShowing=${frame.isShowing}"
        )
        if (gc != null) {
            println(
                "frame.gc.scaleX=${gc.defaultTransform.scaleX} scaleY=${gc.defaultTransform.scaleY} " +
                    "bounds=${gc.bounds}"
            )
        }
    }
    if (panel != null) {
        val loc =
            try {
                panel.locationOnScreen
            } catch (_: java.awt.IllegalComponentStateException) {
                null
            }
        println("panel.size=(w=${panel.width},h=${panel.height}) panel.locationOnScreen=$loc")
    }
    println("counterBounds(Compose px)=${state.counterBounds}")
    println("textFieldBounds(Compose px)=${state.textFieldBounds}")
    println("--- /environment ---")
}

private fun waitForFrame(ref: AtomicReference<JFrame>) {
    val deadline = System.nanoTime() + WINDOW_OPEN_TIMEOUT_MS * 1_000_000L
    while (System.nanoTime() < deadline) {
        val frame = ref.get()
        if (frame != null && frame.isShowing) return
        Thread.sleep(50)
    }
    error("frame never showed within ${WINDOW_OPEN_TIMEOUT_MS}ms")
}

private fun warmupRobot(driver: RobotDriver, state: SmokeState) {
    // Click on the counter button, fire-and-forget. counterBounds is non-zero by here
    // (waitForLayout already proved it). Each scenario captures its own `before` snapshot,
    // so we don't need to reset state — the warmup click just primes the input pipeline.
    val target = awtCenter(state, state.counterBounds) ?: return
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
}

private fun waitForLayout(state: SmokeState) {
    val deadline = System.nanoTime() + LAYOUT_TIMEOUT_MS * 1_000_000L
    while (System.nanoTime() < deadline) {
        val counter = state.counterBounds
        val textField = state.textFieldBounds
        val ready =
            counter.width > 0f &&
                counter.height > 0f &&
                textField.width > 0f &&
                textField.height > 0f
        if (ready) return
        Thread.sleep(50)
    }
    error(
        "Compose targets never laid out within ${LAYOUT_TIMEOUT_MS}ms; " +
            "counterBounds=${state.counterBounds} textFieldBounds=${state.textFieldBounds}"
    )
}

private data class ScenarioResult(val label: String, val passed: Boolean, val detail: String)

private fun scenarioCounterClick(driver: RobotDriver, state: SmokeState): ScenarioResult {
    val before = state.clickCount
    val target = awtCenter(state, state.counterBounds)
    if (target == null) {
        return ScenarioResult("mouseMove + click on counter", false, "no target rect available")
    }
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val after = state.clickCount
    return ScenarioResult(
        "mouseMove + click on counter",
        passed = after == before + 1,
        detail = "clickCount $before → $after at ($${target.x},${target.y})",
    )
}

private fun scenarioCounterDoubleClick(driver: RobotDriver, state: SmokeState): ScenarioResult {
    val before = state.clickCount
    val target = awtCenter(state, state.counterBounds)
    if (target == null) {
        return ScenarioResult("doubleClick on counter", false, "no target rect available")
    }
    driver.doubleClick(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val after = state.clickCount
    return ScenarioResult(
        "doubleClick on counter",
        passed = after == before + 2,
        detail = "clickCount $before → $after",
    )
}

private fun scenarioPressKeySingleChar(driver: RobotDriver, state: SmokeState): ScenarioResult {
    // Baseline for the typeText scenario: if the BasicTextField is focused after a click and
    // accepts direct keystrokes (no clipboard involved), the focus path works and any
    // typeText failure isolates to the clipboard-paste path.
    val target = awtCenter(state, state.textFieldBounds)
    if (target == null) {
        return ScenarioResult("pressKey 'h' into BasicTextField", false, "no target rect available")
    }
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val before = state.textValue.text
    driver.pressKey(java.awt.event.KeyEvent.VK_H)
    Thread.sleep(POST_TYPE_SETTLE_MS)
    val after = state.textValue.text
    return ScenarioResult(
        "pressKey 'h' into BasicTextField",
        passed = after.length == before.length + 1,
        detail = "before=\"$before\" after=\"$after\"",
    )
}

private fun scenarioTypeText(driver: RobotDriver, state: SmokeState): ScenarioResult {
    val expected = "hello windows"
    val target = awtCenter(state, state.textFieldBounds)
    if (target == null) {
        return ScenarioResult("typeText into BasicTextField", false, "no target rect available")
    }
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val focusOwnerBefore = focusOwnerSummary(state)
    driver.typeText(expected)
    Thread.sleep(POST_TYPE_SETTLE_MS)
    val actual = state.textValue.text
    // Exact equality: the field starts empty (typeText runs first in the scenario order
    // before any keystroke leaves state behind), so the only correct outcome is the typed
    // string — substring match would mask paste-doubling or stray-keypress bugs.
    val passed = actual == expected
    val detail = "text=\"$actual\" expected=\"$expected\" focusOwner=$focusOwnerBefore"
    return ScenarioResult("typeText into BasicTextField", passed = passed, detail = detail)
}

private fun focusOwnerSummary(state: SmokeState): String {
    val focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    return focused?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it)}" }
        ?: "null (frame.isFocused=${state.frame?.isFocused})"
}

private fun scenarioClearAndTypeText(driver: RobotDriver, state: SmokeState): ScenarioResult {
    val expected = "replaced"
    val target = awtCenter(state, state.textFieldBounds)
    if (target == null) {
        return ScenarioResult("clearAndTypeText", false, "no target rect available")
    }
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    driver.clearAndTypeText(expected)
    Thread.sleep(POST_TYPE_SETTLE_MS)
    val actual = state.textValue.text
    return ScenarioResult(
        "clearAndTypeText",
        passed = actual == expected,
        detail = "text=\"$actual\" expected=\"$expected\"",
    )
}

private fun scenarioCtrlSShortcut(driver: RobotDriver, state: SmokeState): ScenarioResult {
    state.shortcutFiredCount = 0
    val before = state.shortcutFiredCount
    // Click somewhere inside the panel first to ensure focus is on the Compose root.
    val anchor = awtCenter(state, state.counterBounds)
    if (anchor != null) {
        driver.click(anchor.x, anchor.y)
        Thread.sleep(POST_CLICK_SETTLE_MS)
    }
    driver.pressKey(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val after = state.shortcutFiredCount
    return ScenarioResult(
        "Ctrl+S shortcut via pressKey",
        passed = after == before + 1,
        detail = "shortcutFiredCount $before → $after",
    )
}

private fun awtCenter(state: SmokeState, rect: Rect): java.awt.Point? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    val frame = state.frame ?: return null
    val panel = state.composePanel ?: return null
    val gc = (frame as Window).graphicsConfiguration ?: return null
    val xform = gc.defaultTransform
    val panelLoc =
        try {
            panel.locationOnScreen
        } catch (_: java.awt.IllegalComponentStateException) {
            return null
        }
    return composeBoundsToAwtCenter(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        scaleX = xform.scaleX.toFloat(),
        scaleY = xform.scaleY.toFloat(),
        panelScreenX = panelLoc.x,
        panelScreenY = panelLoc.y,
    )
}

private class SmokeState {
    var frame: JFrame? = null
    var composePanel: ComposePanel? = null

    @Volatile var clickCount: Int = 0
    @Volatile var textValue: TextFieldValue = TextFieldValue("")
    @Volatile var shortcutFiredCount: Int = 0
    @Volatile var counterBounds: Rect = Rect.Zero
    @Volatile var textFieldBounds: Rect = Rect.Zero
}

@Composable
private fun SmokeContent(state: SmokeState) {
    var localCount by remember { mutableIntStateOf(0) }
    var localText by remember { mutableStateOf(TextFieldValue("")) }
    Column(
        modifier =
            Modifier.fillMaxWidth().background(Color.White).padding(16.dp).onPreviewKeyEvent { event
                ->
                if (
                    event.type == KeyEventType.KeyDown &&
                        event.key == Key.S &&
                        (event.isCtrlPressed || event.isMetaPressed)
                ) {
                    state.shortcutFiredCount += 1
                    true
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("Spectre Robot smoke — driven by RobotDriver(), not synthetic.")
        BasicText("clickCount = $localCount")
        Box(
            modifier =
                Modifier.height(48.dp)
                    .fillMaxWidth()
                    .clickable {
                        localCount += 1
                        state.clickCount = localCount
                    }
                    .background(Color(red = 0x33, green = 0x66, blue = 0xCC))
                    .padding(12.dp)
                    .onGloballyPositioned { coords ->
                        state.counterBounds = coords.boundsInWindow()
                    }
        ) {
            BasicText("counter button (click via Robot)")
        }
        // BasicTextField lays out at its intrinsic single-line height by default; without
        // forcing a larger hit area, a Robot click aimed at the wrapper Box's center lands in
        // empty padding below the text input and never focuses the field. Make the
        // BasicTextField fill the wrapper so the entire visible area accepts focus on click.
        BasicTextField(
            value = localText,
            onValueChange = {
                localText = it
                state.textValue = it
            },
            modifier =
                Modifier.fillMaxWidth()
                    .height(48.dp)
                    .background(Color(red = 0xEE, green = 0xEE, blue = 0xEE))
                    .padding(8.dp)
                    .onGloballyPositioned { coords ->
                        state.textFieldBounds = coords.boundsInWindow()
                    },
        )
        BasicText("textValue = \"${state.textValue.text}\"")
        BasicText("shortcutFiredCount = ${state.shortcutFiredCount}")
    }
}

private const val PANEL_WIDTH = 540
private const val PANEL_HEIGHT = 320
private const val WINDOW_OPEN_TIMEOUT_MS = 5_000L
private const val LAYOUT_TIMEOUT_MS = 5_000L
private const val POST_LAYOUT_WARMUP_MS = 500L
private const val POST_CLICK_SETTLE_MS = 250L
private const val POST_TYPE_SETTLE_MS = 800L

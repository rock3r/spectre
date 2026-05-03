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
import dev.sebastiano.spectre.core.detectMacOs
import dev.sebastiano.spectre.core.shortcutModifierKeyCode
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import kotlin.math.abs

internal class SmokeState {
    var frame: JFrame? = null
    var composePanel: ComposePanel? = null

    @Volatile var clickCount: Int = 0
    @Volatile var textValue: TextFieldValue = TextFieldValue("")
    @Volatile var shortcutFiredCount: Int = 0
    @Volatile var counterBounds: Rect = Rect.Zero
    @Volatile var textFieldBounds: Rect = Rect.Zero
    @Volatile var colorPatchBounds: Rect = Rect.Zero
}

@Composable
internal fun SmokeContent(state: SmokeState) {
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
        // Dedicated colour patch for the screenshot scenario. Same RGB as the counter Box
        // background, but no `clickable` modifier and no children — so Compose Foundation's
        // default LocalIndication can never apply its ~10% black focus overlay here, and no
        // glyph rendering can contaminate sampled pixels. The counter Box (which IS clickable
        // and gets focused by every prior interaction scenario) would have been captured at
        // 0.9 × #3366CC + 0.1 × #000000 = #2E5CB7 — the patch reads the raw colour with no
        // overlay, so SCREENSHOT_TOLERANCE_PER_CHANNEL only needs to absorb 8-bit rounding.
        Box(
            modifier =
                Modifier.height(24.dp)
                    .fillMaxWidth()
                    .background(Color(red = 0x33, green = 0x66, blue = 0xCC))
                    .onGloballyPositioned { coords ->
                        state.colorPatchBounds = coords.boundsInWindow()
                    }
        )
    }
}

internal data class ScenarioResult(val label: String, val passed: Boolean, val detail: String)

internal fun waitForFrame(ref: AtomicReference<JFrame>) {
    val deadline = System.nanoTime() + WINDOW_OPEN_TIMEOUT_MS * 1_000_000L
    while (System.nanoTime() < deadline) {
        val frame = ref.get()
        if (frame != null && frame.isShowing) return
        Thread.sleep(50)
    }
    error("frame never showed within ${WINDOW_OPEN_TIMEOUT_MS}ms")
}

internal fun waitForLayout(state: SmokeState) {
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

internal fun printEnvironment(label: String, state: SmokeState) {
    val frame = state.frame
    val panel = state.composePanel
    println("--- $label environment ---")
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

internal fun awtCenter(state: SmokeState, rect: Rect): java.awt.Point? {
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

internal fun warmupRobot(driver: RobotDriver, state: SmokeState) {
    // Click on the counter button, fire-and-forget. counterBounds is non-zero by here
    // (waitForLayout already proved it). Each scenario captures its own `before` snapshot,
    // so we don't need to reset state — the warmup click just primes the input pipeline.
    val target = awtCenter(state, state.counterBounds) ?: return
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
}

internal fun focusOwnerSummary(state: SmokeState): String {
    val focused = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    return focused?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it)}" }
        ?: "null (frame.isFocused=${state.frame?.isFocused})"
}

internal fun scenarioCounterClick(driver: RobotDriver, state: SmokeState): ScenarioResult {
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
        detail = "clickCount $before → $after at (${target.x},${target.y})",
    )
}

internal fun scenarioCounterDoubleClick(driver: RobotDriver, state: SmokeState): ScenarioResult {
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

internal fun scenarioPressKeySingleChar(driver: RobotDriver, state: SmokeState): ScenarioResult {
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

internal fun scenarioTypeText(
    driver: RobotDriver,
    state: SmokeState,
    expected: String,
): ScenarioResult {
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

internal fun scenarioClearAndTypeText(driver: RobotDriver, state: SmokeState): ScenarioResult {
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

internal fun scenarioShortcut(driver: RobotDriver, state: SmokeState): ScenarioResult {
    state.shortcutFiredCount = 0
    val before = state.shortcutFiredCount
    // Click somewhere inside the panel first to ensure focus is on the Compose root.
    val anchor = awtCenter(state, state.counterBounds)
    if (anchor != null) {
        driver.click(anchor.x, anchor.y)
        Thread.sleep(POST_CLICK_SETTLE_MS)
    }
    // Use the platform-aware modifier so the same scenario exercises Ctrl+S on Windows/Linux
    // and Cmd+S on macOS — `shortcutModifierKeyCode` is what RobotDriver itself uses for
    // typeText/clearAndTypeText, so the smoke proves the same modifier-mask → keyCode path
    // Spectre callers use for shortcut keystrokes.
    val modifierMask =
        if (detectMacOs()) java.awt.event.InputEvent.META_DOWN_MASK
        else java.awt.event.InputEvent.CTRL_DOWN_MASK
    driver.pressKey(java.awt.event.KeyEvent.VK_S, modifierMask)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val after = state.shortcutFiredCount
    val modifierLabel = if (detectMacOs()) "Cmd+S" else "Ctrl+S"
    return ScenarioResult(
        "$modifierLabel shortcut via pressKey",
        passed = after == before + 1,
        detail =
            "shortcutFiredCount $before → $after (modifier keyCode=" +
                "${shortcutModifierKeyCode(detectMacOs())})",
    )
}

internal fun scenarioScreenshot(driver: RobotDriver, state: SmokeState): ScenarioResult {
    val target = awtCenter(state, state.colorPatchBounds)
    if (target == null) {
        return ScenarioResult("screenshot of color patch", false, "no target rect available")
    }
    // The colour patch is a non-clickable, no-children Box dedicated to this scenario, so the
    // sampled pixel is the raw background colour with no focus-indication overlay or glyph
    // contamination — see SmokeContent's colorPatchBounds element for the rationale.
    val captureRegion =
        Rectangle(
            target.x - SCREENSHOT_CAPTURE_HALF_WIDTH,
            target.y - SCREENSHOT_CAPTURE_HEIGHT / 2,
            SCREENSHOT_CAPTURE_HALF_WIDTH * 2,
            SCREENSHOT_CAPTURE_HEIGHT,
        )
    val image =
        try {
            driver.screenshot(captureRegion)
        } catch (t: Throwable) {
            return ScenarioResult(
                "screenshot of color patch",
                false,
                "screenshot threw: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
    if (image.width <= 0 || image.height <= 0) {
        return ScenarioResult(
            "screenshot of color patch",
            false,
            "image had non-positive dimensions ${image.width}x${image.height}",
        )
    }
    val sampledArgb = image.getRGB(image.width / 2, image.height / 2)
    val sampledRgb = sampledArgb and 0xFFFFFF
    if (sampledRgb == 0) {
        // macOS Screen Recording denied returns an all-black image rather than throwing.
        // Linux/Windows can't normally hit this — the JFrame is alwaysOnTop and visible —
        // so a black sample on those platforms suggests the panel was off-screen, occluded,
        // or the capture rect landed outside the frame.
        return ScenarioResult(
            "screenshot of color patch",
            false,
            "sampled center pixel is solid black (#000000) — on macOS this typically means " +
                "Screen Recording TCC was not granted (createScreenCapture returns black " +
                "rather than throwing); on Linux/Windows it suggests the JFrame was occluded " +
                "or off-screen when the capture fired",
        )
    }
    val expectedRgb = SCREENSHOT_COUNTER_BG_RGB
    val passed = colourWithinTolerance(sampledRgb, expectedRgb, SCREENSHOT_TOLERANCE_PER_CHANNEL)
    return ScenarioResult(
        "screenshot of color patch",
        passed = passed,
        detail =
            "sampled=#${"%06X".format(sampledRgb)} expected=#${"%06X".format(expectedRgb)} " +
                "image=${image.width}x${image.height} captureRegion=" +
                "(${captureRegion.x},${captureRegion.y})+${captureRegion.width}x${captureRegion.height}",
    )
}

private fun colourWithinTolerance(a: Int, b: Int, perChannel: Int): Boolean {
    val ar = (a shr 16) and 0xFF
    val ag = (a shr 8) and 0xFF
    val ab = a and 0xFF
    val br = (b shr 16) and 0xFF
    val bg = (b shr 8) and 0xFF
    val bb = b and 0xFF
    return abs(ar - br) <= perChannel && abs(ag - bg) <= perChannel && abs(ab - bb) <= perChannel
}

internal fun runFocusedScenarios(
    driver: RobotDriver,
    state: SmokeState,
    typeTextExpected: String,
): List<ScenarioResult> {
    val results = mutableListOf<ScenarioResult>()
    // Order matters: typeText asserts exact equality on a freshly-empty field, so it has to
    // run before any keystroke that would have left state in the BasicTextField. After that,
    // pressKey appends one character (verifying focus + raw keystrokes), and clearAndTypeText
    // wipes and re-types (verifying the Ctrl+A / Cmd+A + Backspace path). Screenshot runs
    // last because it's the only scenario that triggers macOS's Screen Recording TCC prompt;
    // a mid-run prompt steals focus and would break any subsequent keystroke scenario, so by
    // running it after all input scenarios, the worst case is just the screenshot scenario
    // failing on a first-run grant attempt — re-running passes once TCC is granted.
    results += scenarioCounterClick(driver, state)
    results += scenarioCounterDoubleClick(driver, state)
    results += scenarioTypeText(driver, state, expected = typeTextExpected)
    results += scenarioPressKeySingleChar(driver, state)
    results += scenarioClearAndTypeText(driver, state)
    results += scenarioShortcut(driver, state)
    results += scenarioScreenshot(driver, state)
    return results
}

internal fun scenarioStartsUnfocused(state: SmokeState, distractor: JFrame?): ScenarioResult {
    val sut = state.frame
    val sutFocused = sut?.isFocused == true
    val distractorFocused = distractor?.isFocused == true
    return ScenarioResult(
        "starting state: distractor focused, SUT not focused",
        passed = !sutFocused && distractorFocused,
        detail = "sut.isFocused=$sutFocused distractor.isFocused=$distractorFocused",
    )
}

internal fun scenarioPressKeyOnUnfocusedField(
    driver: RobotDriver,
    state: SmokeState,
): ScenarioResult {
    // SUT is unfocused — no Compose component holds keyboard focus on the SUT side. Pressing
    // a character key should NOT modify the text field. If it does, focus must have leaked
    // somewhere we don't expect (or Robot is targeting the distractor's contents instead).
    val before = state.textValue.text
    driver.pressKey(java.awt.event.KeyEvent.VK_X)
    Thread.sleep(POST_TYPE_SETTLE_MS)
    val after = state.textValue.text
    return ScenarioResult(
        "pressKey on unfocused SUT does NOT alter the text field",
        passed = after == before,
        detail = "before=\"$before\" after=\"$after\" sut.isFocused=${state.frame?.isFocused}",
    )
}

internal fun scenarioClickOnUnfocusedCounter(
    driver: RobotDriver,
    state: SmokeState,
): ScenarioResult {
    val target = awtCenter(state, state.counterBounds)
    if (target == null) {
        return ScenarioResult("click on unfocused counter", false, "no target rect available")
    }
    val before = state.clickCount
    val sutFocusedBefore = state.frame?.isFocused == true
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val after = state.clickCount
    val sutFocusedAfter = state.frame?.isFocused == true
    return ScenarioResult(
        "click on unfocused counter increments + transfers focus",
        passed = after == before + 1 && sutFocusedAfter,
        detail =
            "clickCount $before → $after; sut.isFocused $sutFocusedBefore → $sutFocusedAfter " +
                "at (${target.x},${target.y})",
    )
}

internal fun scenarioTypeTextAfterFocusClick(
    driver: RobotDriver,
    state: SmokeState,
): ScenarioResult {
    val expected = "post-focus paste"
    val target = awtCenter(state, state.textFieldBounds)
    if (target == null) {
        return ScenarioResult("typeText after focus-handoff click", false, "no target rect")
    }
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    driver.clearAndTypeText(expected)
    Thread.sleep(POST_TYPE_SETTLE_MS)
    val actual = state.textValue.text
    return ScenarioResult(
        "typeText after focus-handoff click",
        passed = actual == expected,
        detail = "text=\"$actual\" expected=\"$expected\"",
    )
}

internal fun runUnfocusedScenarios(
    driver: RobotDriver,
    state: SmokeState,
    distractor: JFrame,
): List<ScenarioResult> {
    val results = mutableListOf<ScenarioResult>()
    results += scenarioStartsUnfocused(state, distractor)
    // Robot warmup click on the *distractor* — primes the input pipeline (the same cold-JVM
    // dropped-first-click flake the focused smoke documents) without clicking SUT, because
    // clicking SUT would transfer focus to it and ruin the unfocused starting state.
    val distractorBounds = distractor.bounds
    driver.click(distractorBounds.centerX.toInt(), distractorBounds.centerY.toInt())
    Thread.sleep(POST_CLICK_SETTLE_MS)
    results += scenarioPressKeyOnUnfocusedField(driver, state)
    results += scenarioClickOnUnfocusedCounter(driver, state)
    results += scenarioTypeTextAfterFocusClick(driver, state)
    return results
}

internal fun printResults(label: String, results: List<ScenarioResult>): Int {
    println()
    println("--- $label results ---")
    results.forEach {
        println("  ${it.label}: ${if (it.passed) "PASS" else "FAIL"} — ${it.detail}")
    }
    val passed = results.count { it.passed }
    val total = results.size
    println("--- $passed/$total passed ---")
    return if (passed == total) 0 else 1
}

internal const val SMOKE_PANEL_WIDTH = 540
internal const val SMOKE_PANEL_HEIGHT = 320
internal const val WINDOW_OPEN_TIMEOUT_MS = 5_000L
internal const val LAYOUT_TIMEOUT_MS = 5_000L
internal const val POST_LAYOUT_WARMUP_MS = 500L
// Unfocused smokes spawn a sibling distractor JFrame and exercise focus transfers between two
// AWT windows; the AWT input handlers attach a few frames later than the focused case, so the
// original WindowsRobotUnfocusedSmoke used 600ms here. Restored as a separate constant after
// the rig refactor standardised on 500ms — the focus-transfer settle is the why.
internal const val POST_LAYOUT_UNFOCUSED_WARMUP_MS = 600L
internal const val POST_CLICK_SETTLE_MS = 250L
internal const val POST_TYPE_SETTLE_MS = 800L

// Screenshot scenario constants. The colour patch in SmokeContent uses
// Color(0x33, 0x66, 0xCC) and is intentionally non-clickable + has no children, so the
// captured pixel is the raw background colour with no overlays. ±3 per channel covers
// 8-bit rounding (sRGB→display→pixel quantisation) with no further headroom needed.
//
// The earlier ±24 tolerance was a workaround for ~21-channel drift observed when the
// scenario captured the *counter* Box: that Box has `.clickable {}`, every prior interaction
// scenario clicked it, and Compose Foundation's default LocalIndication painted a persistent
// ~10% black overlay on focused clickables. The captured pixel was 0.9 × #3366CC + 0.1 ×
// #000000 = #2E5CB7 — exactly the observed drift. Capturing a dedicated non-interactive
// patch eliminates the overlay entirely; the previous "sRGB↔linear" hypothesis was wrong.
private const val SCREENSHOT_COUNTER_BG_RGB = 0x3366CC
private const val SCREENSHOT_TOLERANCE_PER_CHANNEL = 3
private const val SCREENSHOT_CAPTURE_HALF_WIDTH = 8
private const val SCREENSHOT_CAPTURE_HEIGHT = 8

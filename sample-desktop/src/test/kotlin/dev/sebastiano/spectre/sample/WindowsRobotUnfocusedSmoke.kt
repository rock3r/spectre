@file:JvmName("WindowsRobotUnfocusedSmoke")

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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.composeBoundsToAwtCenter
import java.awt.Color as AwtColor
import java.awt.Dimension
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual smoke for #59: drive [RobotDriver] against a Compose window that is **deliberately not the
 * foreground / focused window**. Run via `./gradlew :sample-desktop:runWindowsRobotUnfocusedSmoke`.
 *
 * Companion to [WindowsRobotSmoke]. That smoke runs against an `isAlwaysOnTop=true` JFrame whose
 * focus is naturally retained — so it tests the focused-input path. This smoke spawns a separate
 * distractor JFrame, brings *that* to the front, then drives Robot at the original SUT which is now
 * visible-but-unfocused. The assertions cover what a real Spectre user sees when their automation
 * runs alongside other foreground apps.
 *
 * Both JFrames are `isAlwaysOnTop=true` so they stay above the Gradle/IntelliJ terminal that
 * spawned the JVM (Windows foreground-stealing prevention — see the [WindowsRobotSmoke] KDoc for
 * the full reason). Within the alwaysOnTop layer the JFrames do honour each other's `toFront()`,
 * which is what lets us hand focus to the distractor while SUT stays visible.
 *
 * Findings on Windows 11 + JBR 21 (4/4 PASS):
 *
 * 1. **`toFront()` on a distractor JFrame does steal focus from the SUT, even when both JFrames are
 *    `isAlwaysOnTop=true`.** Reading `sut.isFocused` after the distractor's `requestFocus()`
 *    returns `false`; reading `distractor.isFocused` returns `true`. So "unfocused-but-visible
 *    Compose window" is a legitimate state we can construct + drive.
 * 2. **`pressKey` aimed at an unfocused SUT is dropped** — no Compose component holds keyboard
 *    focus on the SUT side, so the BasicTextField's content is unchanged after `pressKey(VK_X)`.
 *    Spectre callers cannot rely on keystrokes alone reaching an unfocused target window; a
 *    focusing click must come first.
 * 3. **`click()` on the unfocused counter both registers the click AND transfers focus to the SUT**
 *    — the counter increments and `sut.isFocused` flips from `false` to `true` in the same Robot
 *    mouse event. Windows' standard click-to-focus semantics for top-level windows hold here: the
 *    first click is "free" — it counts as both a real interaction *and* a focus handoff. Spectre
 *    automation does NOT need to dispatch a separate activation click before driving an inactive
 *    Compose window.
 * 4. **`typeText` works after the focus-handoff click** — the existing clipboard-paste path
 *    (`clearAndTypeText("post-focus paste")`) lands the exact expected string in the text field.
 *    Once focus has moved, this is the same path as the focused smoke; no Windows- specific quirk
 *    in the unfocused-then-focused transition.
 *
 * Net effect: Spectre callers can safely drive a Compose window that's not currently foreground.
 * Document for users running automation alongside other apps that the FIRST Robot mouse event acts
 * as both interaction AND focus claim, which matches macOS click-to- focus behaviour and is the
 * same expectation Spectre's existing API design assumes.
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
    val state = UnfocusedSmokeState()
    val sutRef = AtomicReference<JFrame>()
    val distractorRef = AtomicReference<JFrame>()

    SwingUtilities.invokeLater {
        // SUT first, positioned away from the screen origin so the distractor (placed at the
        // origin) doesn't overlap it. No `toFront`/`requestFocus` — we *want* the distractor
        // to be the focus owner after both are on screen.
        val composePanel =
            ComposePanel().apply {
                preferredSize = Dimension(PANEL_WIDTH, PANEL_HEIGHT)
                setContent { UnfocusedSmokeContent(state) }
            }
        val sut =
            JFrame("Spectre unfocused-robot SUT").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = composePanel
                pack()
                setLocation(SUT_X, SUT_Y)
                isAlwaysOnTop = true
                isVisible = true
            }
        state.frame = sut
        state.composePanel = composePanel
        sutRef.set(sut)

        val distractor =
            JFrame("distractor").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                preferredSize = Dimension(DISTRACTOR_WIDTH, DISTRACTOR_HEIGHT)
                contentPane =
                    JLabel("distractor — focus stealer", SwingConstants.CENTER).apply {
                        background = AwtColor.YELLOW
                        isOpaque = true
                    }
                pack()
                setLocation(DISTRACTOR_X, DISTRACTOR_Y)
                isAlwaysOnTop = true
                isVisible = true
                toFront()
                requestFocus()
            }
        distractorRef.set(distractor)
    }

    waitForFrame(sutRef)
    waitForFrame(distractorRef)
    waitForLayout(state)
    Thread.sleep(POST_LAYOUT_WARMUP_MS)

    val driver = RobotDriver()
    val results = mutableListOf<UnfocusedScenarioResult>()

    // Pin the unfocused starting state. If this ever flips on a future Windows / JBR build
    // the rest of the assertions become meaningless (we'd be testing the focused path under
    // an unfocused-named smoke), so fail loudly here.
    results += scenarioStartsUnfocused(state, distractorRef.get())

    // Robot warmup click on the *distractor* — we want to prime the input pipeline (the same
    // cold-JVM dropped-first-click flake the focused smoke documents) without clicking SUT,
    // because clicking SUT would transfer focus to it and ruin the unfocused starting state.
    val distractor = requireNotNull(distractorRef.get()) { "distractor frame missing" }
    val distractorBounds = distractor.bounds
    driver.click(distractorBounds.centerX.toInt(), distractorBounds.centerY.toInt())
    Thread.sleep(POST_CLICK_SETTLE_MS)

    results += scenarioPressKeyOnUnfocusedField(driver, state)
    results += scenarioClickOnUnfocusedCounter(driver, state)
    results += scenarioTypeTextAfterFocusClick(driver, state)

    println()
    println("--- WindowsRobotUnfocusedSmoke results ---")
    results.forEach {
        println("  ${it.label}: ${if (it.passed) "PASS" else "FAIL"} — ${it.detail}")
    }
    val passed = results.count { it.passed }
    val total = results.size
    println("--- $passed/$total passed ---")

    SwingUtilities.invokeLater {
        sutRef.get()?.dispose()
        distractorRef.get()?.dispose()
    }

    return if (passed == total) 0 else 1
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

private fun waitForLayout(state: UnfocusedSmokeState) {
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

private data class UnfocusedScenarioResult(
    val label: String,
    val passed: Boolean,
    val detail: String,
)

private fun scenarioStartsUnfocused(
    state: UnfocusedSmokeState,
    distractor: JFrame?,
): UnfocusedScenarioResult {
    val sut = state.frame
    val sutFocused = sut?.isFocused == true
    val distractorFocused = distractor?.isFocused == true
    return UnfocusedScenarioResult(
        "starting state: distractor focused, SUT not focused",
        passed = !sutFocused && distractorFocused,
        detail = "sut.isFocused=$sutFocused distractor.isFocused=$distractorFocused",
    )
}

private fun scenarioPressKeyOnUnfocusedField(
    driver: RobotDriver,
    state: UnfocusedSmokeState,
): UnfocusedScenarioResult {
    // SUT is unfocused — no Compose component holds keyboard focus on the SUT side. Pressing
    // a character key should NOT modify the text field. If it does, focus must have leaked
    // somewhere we don't expect (or Robot is targeting the distractor's contents instead).
    val before = state.textValue.text
    driver.pressKey(java.awt.event.KeyEvent.VK_X)
    Thread.sleep(POST_TYPE_SETTLE_MS)
    val after = state.textValue.text
    return UnfocusedScenarioResult(
        "pressKey on unfocused SUT does NOT alter the text field",
        passed = after == before,
        detail = "before=\"$before\" after=\"$after\" sut.isFocused=${state.frame?.isFocused}",
    )
}

private fun scenarioClickOnUnfocusedCounter(
    driver: RobotDriver,
    state: UnfocusedSmokeState,
): UnfocusedScenarioResult {
    val target = awtCenter(state, state.counterBounds)
    if (target == null) {
        return UnfocusedScenarioResult(
            "click on unfocused counter",
            false,
            "no target rect available",
        )
    }
    val before = state.clickCount
    val sutFocusedBefore = state.frame?.isFocused == true
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val after = state.clickCount
    val sutFocusedAfter = state.frame?.isFocused == true
    return UnfocusedScenarioResult(
        "click on unfocused counter increments + transfers focus",
        passed = after == before + 1 && sutFocusedAfter,
        detail =
            "clickCount $before → $after; sut.isFocused $sutFocusedBefore → $sutFocusedAfter " +
                "at (${target.x},${target.y})",
    )
}

private fun scenarioTypeTextAfterFocusClick(
    driver: RobotDriver,
    state: UnfocusedSmokeState,
): UnfocusedScenarioResult {
    val expected = "post-focus paste"
    val target = awtCenter(state, state.textFieldBounds)
    if (target == null) {
        return UnfocusedScenarioResult(
            "typeText after focus-handoff click",
            false,
            "no target rect",
        )
    }
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    driver.clearAndTypeText(expected)
    Thread.sleep(POST_TYPE_SETTLE_MS)
    val actual = state.textValue.text
    return UnfocusedScenarioResult(
        "typeText after focus-handoff click",
        passed = actual == expected,
        detail = "text=\"$actual\" expected=\"$expected\"",
    )
}

private fun awtCenter(state: UnfocusedSmokeState, rect: Rect): java.awt.Point? {
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

private class UnfocusedSmokeState {
    var frame: JFrame? = null
    var composePanel: ComposePanel? = null

    @Volatile var clickCount: Int = 0
    @Volatile var textValue: TextFieldValue = TextFieldValue("")
    @Volatile var counterBounds: Rect = Rect.Zero
    @Volatile var textFieldBounds: Rect = Rect.Zero
}

@Composable
private fun UnfocusedSmokeContent(state: UnfocusedSmokeState) {
    var localCount by remember { mutableIntStateOf(0) }
    var localText by remember { mutableStateOf(TextFieldValue("")) }
    Column(
        modifier = Modifier.fillMaxWidth().background(Color.White).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("Spectre unfocused-robot smoke — driven by RobotDriver, distractor steals focus.")
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
            BasicText("counter button (click via Robot while unfocused)")
        }
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
    }
}

private const val PANEL_WIDTH = 540
private const val PANEL_HEIGHT = 280
private const val SUT_X = 600
private const val SUT_Y = 400
private const val DISTRACTOR_WIDTH = 320
private const val DISTRACTOR_HEIGHT = 200
private const val DISTRACTOR_X = 80
private const val DISTRACTOR_Y = 80
private const val WINDOW_OPEN_TIMEOUT_MS = 5_000L
private const val LAYOUT_TIMEOUT_MS = 5_000L
private const val POST_LAYOUT_WARMUP_MS = 600L
private const val POST_CLICK_SETTLE_MS = 250L
private const val POST_TYPE_SETTLE_MS = 800L

@file:JvmName("WindowsRobotUnfocusedSmoke")

package dev.sebastiano.spectre.sample

import androidx.compose.ui.awt.ComposePanel
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Color as AwtColor
import java.awt.Dimension
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
    val state = SmokeState()
    val sutRef = AtomicReference<JFrame>()
    val distractorRef = AtomicReference<JFrame>()

    SwingUtilities.invokeLater {
        // SUT first, positioned away from the screen origin so the distractor (placed at the
        // origin) doesn't overlap it. No `toFront`/`requestFocus` — we *want* the distractor
        // to be the focus owner after both are on screen.
        val composePanel =
            ComposePanel().apply {
                preferredSize = Dimension(SMOKE_PANEL_WIDTH, SMOKE_PANEL_HEIGHT)
                setContent { SmokeContent(state) }
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
    Thread.sleep(POST_LAYOUT_UNFOCUSED_WARMUP_MS)

    val driver = RobotDriver()
    val distractor = requireNotNull(distractorRef.get()) { "distractor frame missing" }
    val results = runUnfocusedScenarios(driver, state, distractor)
    val exitCode = printResults("WindowsRobotUnfocusedSmoke", results)

    SwingUtilities.invokeLater {
        sutRef.get()?.dispose()
        distractorRef.get()?.dispose()
    }

    return exitCode
}

private const val SUT_X = 600
private const val SUT_Y = 400
private const val DISTRACTOR_WIDTH = 320
private const val DISTRACTOR_HEIGHT = 200
private const val DISTRACTOR_X = 80
private const val DISTRACTOR_Y = 80

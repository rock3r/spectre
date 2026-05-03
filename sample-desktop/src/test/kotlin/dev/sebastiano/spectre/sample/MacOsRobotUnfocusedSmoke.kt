@file:JvmName("MacOsRobotUnfocusedSmoke")

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
import kotlinx.coroutines.runBlocking

/**
 * Manual smoke for [RobotDriver] driven at a deliberately-unfocused Compose window on macOS.
 * Companion to [MacOsRobotSmoke] / [WindowsRobotUnfocusedSmoke]. Run via `./gradlew
 * :sample-desktop:runMacOsRobotUnfocusedSmoke`.
 *
 * Same TCC gate as [MacOsRobotSmoke]: probes the SUT counter first; if the click does not
 * increment, prints a remediation message and exits 2 (java.awt.Robot is being silently dropped
 * because the JVM lacks Accessibility permission). No CI here — GitHub-hosted macos-* runners don't
 * grant TCC, same constraint that already keeps SCK end-to-end tests off macos.yml.
 *
 * Findings from running this on macOS (placeholder — bank empirical results here on first real run,
 * mirroring the Windows unfocused smoke's findings list):
 *
 * 1. (TBD — does NSWindow's `orderFront:` followed by another window's activation actually leave
 *    the SUT visible-but-unfocused, or does the WM force one or the other?)
 * 2. (TBD — pressKey-on-unfocused-SUT behaviour: macOS routes synthetic key events to the
 *    front-most app, so this may behave differently from Windows where the SUT's focus owner is
 *    empty.)
 * 3. (TBD — macOS click-to-focus semantics: AppKit traditionally requires a separate activation
 *    click for inactive apps before in-app interactions count, unlike Windows "first click is
 *    free". Empirical observation needed.)
 * 4. (TBD — typeText-after-focus-click stability under NSPasteboard.)
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

private fun runSmoke(): Int = runBlocking { runSmokeSuspend() }

private suspend fun runSmokeSuspend(): Int {
    val state = SmokeState()
    val sutRef = AtomicReference<JFrame>()
    val distractorRef = AtomicReference<JFrame>()

    SwingUtilities.invokeLater {
        val composePanel =
            ComposePanel().apply {
                preferredSize = Dimension(SMOKE_PANEL_WIDTH, SMOKE_PANEL_HEIGHT)
                setContent { SmokeContent(state) }
            }
        val sut =
            JFrame("Spectre unfocused-robot SUT (macOS)").apply {
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
    // Reuse the focused smoke's TCC probe — the unfocused SUT can still receive Robot input
    // routing in principle, but if the JVM lacks Accessibility permission EVERY click is
    // dropped and the assertions become meaningless. Fail loudly with the same remediation.
    // The probe click also functions as the Windows-style cold-JVM warmup click; subsequent
    // assertions capture their own deltas so the prime click doesn't skew them.
    val tccAbort = probeTccAccessibility(driver, state)
    if (tccAbort != null) {
        System.err.println(tccAbort)
        SwingUtilities.invokeLater {
            sutRef.get()?.dispose()
            distractorRef.get()?.dispose()
        }
        return 2
    }
    // The TCC probe transferred focus to the SUT — re-focus the distractor so the unfocused
    // scenarios run from the documented starting state.
    val distractor = requireNotNull(distractorRef.get()) { "distractor frame missing" }
    SwingUtilities.invokeLater {
        distractor.toFront()
        distractor.requestFocus()
    }
    Thread.sleep(POST_CLICK_SETTLE_MS)

    val results = runUnfocusedScenarios(driver, state, distractor)
    val exitCode = printResults("MacOsRobotUnfocusedSmoke", results)

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

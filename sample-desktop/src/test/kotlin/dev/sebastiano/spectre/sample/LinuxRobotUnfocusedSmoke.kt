@file:JvmName("LinuxRobotUnfocusedSmoke")

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
 * Manual smoke for [RobotDriver] driven at a deliberately-unfocused Compose window on Linux.
 * Companion to [LinuxRobotSmoke] / [WindowsRobotUnfocusedSmoke]. Run via `./gradlew
 * :sample-desktop:runLinuxRobotUnfocusedSmoke`.
 *
 * Same gate as [LinuxRobotSmoke]: aborts with exit 2 on pure Wayland (Robot input is dropped at the
 * protocol layer; would report 0/N PASS for opaque reasons).
 *
 * Findings from running this on Linux (placeholder — bank empirical results here on first real run,
 * mirroring the Windows unfocused smoke's findings list):
 *
 * 1. (TBD — does focus actually leave the SUT after the distractor's `requestFocus()` under KWin /
 *    Mutter / Xvfb? WMs honour focus requests differently than Windows.)
 * 2. (TBD — pressKey-on-unfocused-SUT behaviour: dropped, or routed to focus owner?)
 * 3. (TBD — click-to-focus semantics: does the first click both register AND transfer focus, or is
 *    a separate activation click required on the user's WM?)
 * 4. (TBD — typeText-after-focus-click stability under Xorg's clipboard semantics — XSelection
 *    timing differs from Windows OLE clipboard.)
 */
fun main() {
    var exitCode = 0
    try {
        val abort = checkWaylandGate()
        if (abort != null) {
            System.err.println(abort)
            exitProcess(2)
        }
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
        val composePanel =
            ComposePanel().apply {
                preferredSize = Dimension(SMOKE_PANEL_WIDTH, SMOKE_PANEL_HEIGHT)
                setContent { SmokeContent(state) }
            }
        val sut =
            JFrame("Spectre unfocused-robot SUT (Linux)").apply {
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
    val distractor = requireNotNull(distractorRef.get()) { "distractor frame missing" }
    val results = runUnfocusedScenarios(driver, state, distractor)
    val exitCode = printResults("LinuxRobotUnfocusedSmoke", results)

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

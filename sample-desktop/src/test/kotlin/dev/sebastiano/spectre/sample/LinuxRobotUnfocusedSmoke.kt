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
 * Findings from running this on Ubuntu 24.04 (Hyper-V VM, GNOME Wayland session, validated under
 * both the XWayland bridge and headless Xvfb):
 *
 * 1. **Focus actually leaves the SUT after the distractor's `requestFocus()`.** Validated on
 *    Mutter-via-XWayland (GNOME Wayland's X11 server) and on Xvfb's minimal WM. The starting-state
 *    scenario reports `sut.isFocused=false distractor.isFocused=true` reliably across runs — no
 *    additional `toFront()` ceremony or sleep-after-focus-request was needed beyond what the rig
 *    already provides.
 * 2. **pressKey on an unfocused SUT is dropped, not routed to the focus owner.** The empty-text
 *    field stays empty (`before="" after=""`) when keystrokes arrive while the distractor holds
 *    focus, matching Windows behaviour. This is the OS / WM enforcing focus, not a Spectre quirk —
 *    `java.awt.Robot` injects at the X server layer, the X server delivers to the focused window,
 *    XWayland routes to the X-side focus owner.
 * 3. **A single click on the unfocused counter both registers AND transfers focus.** No separate
 *    activation click is needed — `clickCount 0 → 1` increments AND `sut.isFocused false → true` in
 *    the same scenario step, on both XWayland and Xvfb. (Reflects the X11 click-to-focus
 *    convention; differs from macOS, where the first click on an inactive app traditionally
 *    activates without delivering — to be verified separately when MacOsRobotUnfocusedSmoke runs.)
 * 4. **typeText-after-focus-click works through XSelection on XWayland.** No clipboard-settle
 *    pressure observed; the existing `CLIPBOARD_SETTLE_TIMEOUT_MS = 1_000L` budget tuned for macOS
 *    NSPasteboard latency is never approached on X11. Both XWayland and Xvfb produce the expected
 *    text after the focus-handoff click.
 *
 * Three Linux session modes validated (mirrors [LinuxRobotSmoke]'s focused-smoke findings):
 * - **XWayland** (Wayland session with `DISPLAY=:0` set): 4/4 PASS through XWayland's X11 bridge.
 * - **Pure Wayland** (Wayland session with `DISPLAY` unset): gate fires, smoke exits before
 *   constructing the JFrame.
 * - **Headless Xvfb**: 4/4 PASS against the virtual framebuffer; matches the focused smoke's CI
 *   path. Wiring this variant into `validation-linux.yml` is now eligible (the previous "stays
 *   manual until findings replace TBDs" condition is satisfied) and a separate change.
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
    Thread.sleep(POST_LAYOUT_UNFOCUSED_WARMUP_MS)

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

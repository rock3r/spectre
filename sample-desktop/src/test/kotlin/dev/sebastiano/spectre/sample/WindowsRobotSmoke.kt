@file:JvmName("WindowsRobotSmoke")

package dev.sebastiano.spectre.sample

import androidx.compose.ui.awt.ComposePanel
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Dimension
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
                preferredSize = Dimension(SMOKE_PANEL_WIDTH, SMOKE_PANEL_HEIGHT)
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

    printEnvironment("WindowsRobotSmoke", state)

    val driver = RobotDriver()
    // Cold-JVM warmup: the very first Robot mouse event after a freshly-shown alwaysOnTop
    // JFrame is empirically dropped on Windows ~half the time — observed flake where
    // scenario 1's click registers as 0 → 0 but scenario 2's double-click registers as
    // 0 → 2 on the same button. Fire one sacrificial click outside the assertion-bearing
    // scenarios so the production scenarios run on a warm input pipeline.
    warmupRobot(driver, state)
    val results = runFocusedScenarios(driver, state, typeTextExpected = "hello windows")
    val exitCode = printResults("WindowsRobotSmoke", results)

    SwingUtilities.invokeLater {
        state.frame?.isVisible = false
        state.frame?.dispose()
    }

    return exitCode
}

@file:JvmName("MacOsRobotSmoke")

package dev.sebastiano.spectre.sample

import androidx.compose.ui.awt.ComposePanel
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual smoke for [RobotDriver] on macOS. Run via `./gradlew :sample-desktop:runMacOsRobotSmoke`.
 * Mirrors [WindowsRobotSmoke] / [LinuxRobotSmoke] — same six scenarios via the shared
 * [runFocusedScenarios] rig — and reports PASS/FAIL per scenario.
 *
 * **TCC permission gate.** `java.awt.Robot.mousePress` on modern macOS requires the JVM process to
 * hold **Accessibility** permission (System Settings → Privacy & Security → Accessibility), even
 * for input into its own window. Screenshots additionally require **Screen Recording**. Without
 * these permissions, events are silently dropped — the smoke would appear to run but report 0/6
 * PASS for opaque reasons. There's no clean API to query TCC state, so the smoke fires a probe
 * click against the counter; if `clickCount` doesn't increment it prints a remediation message
 * naming the JVM binary path and exits with code 2 BEFORE running the scenario suite.
 *
 * **No CI for this smoke.** GitHub-hosted `macos-*` runners don't grant TCC Accessibility, so this
 * smoke can't run there — same constraint that already keeps SCK end-to-end recording tests off the
 * macos.yml workflow ("CI runners don't have Screen Recording TCC"). The Gradle task is gated
 * `onlyIf isMacOsX` and intended to be run manually on a developer Mac after granting the JVM
 * Accessibility permission.
 *
 * **Modifier key.** `RobotDriver` uses `shortcutModifierKeyCode(detectMacOs())` internally for
 * `typeText` / `clearAndTypeText`, so those run as Cmd+V / Cmd+A on macOS. The shared rig's
 * `scenarioShortcut` uses the same helper, so the Ctrl+S → Cmd+S translation happens automatically
 * without per-platform conditionals here.
 *
 * Findings from running this on macOS (placeholder — bank empirical results here on first real run,
 * mirroring the Windows smoke's findings list):
 *
 * 1. (TBD — does macOS need the alwaysOnTop foreground hack? AppKit has no foreground-stealing-
 *    prevention analogous to Windows, so plain `toFront()` may suffice. Default to alwaysOnTop for
 *    safety and revise after empirical observation.)
 * 2. (TBD — NSPasteboard latency budget under typeText: the existing
 *    `CLIPBOARD_SETTLE_TIMEOUT_MS=1000` was tuned partly for macOS — confirm it holds on cold JVM +
 *    freshly-woken machine.)
 * 3. (TBD — Cmd+S shortcut delivery: `pressKey(VK_S, META_DOWN_MASK)` should fire the Compose
 *    `onPreviewKeyEvent` handler the same way Ctrl+S does on Windows; the handler accepts either
 *    `isCtrlPressed || isMetaPressed`, so a regression here would isolate to Robot's modifier-mask
 *    path, not the smoke's content.)
 *
 * What this *doesn't* exercise:
 * - SCK / screen recording — that's `:recording`'s territory and gated on the macos.yml workflow's
 *   contract test, not on real frame capture.
 * - Background-app input (`apple.awt.UIElement=true` mode used by `validationTest`) — this smoke
 *   runs as a regular foreground app; the UI-element mode bypasses TCC by going through synthetic
 *   AWT events instead of real Robot input.
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
            JFrame("Spectre Robot smoke (macOS)").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = composePanel
                pack()
                setLocationRelativeTo(null)
                // Default to alwaysOnTop for safety. macOS has no foreground-stealing-prevention
                // (unlike Windows), but keeping the fixture predictable regardless of host
                // configuration costs nothing at the OS level. Findings should bank whether
                // this is actually needed once the smoke runs on a real Mac.
                isAlwaysOnTop = true
                isVisible = true
                toFront()
                requestFocus()
            }
        state.frame = frame
        state.composePanel = composePanel
        frameRef.set(frame)
    }

    waitForFrame(frameRef)
    waitForLayout(state)
    Thread.sleep(POST_LAYOUT_WARMUP_MS)

    printEnvironment("MacOsRobotSmoke", state)

    val driver = RobotDriver()
    val tccAbort = probeTccAccessibility(driver, state)
    if (tccAbort != null) {
        System.err.println(tccAbort)
        SwingUtilities.invokeLater {
            state.frame?.isVisible = false
            state.frame?.dispose()
        }
        return 2
    }

    val results = runFocusedScenarios(driver, state, typeTextExpected = "hello macos")
    val exitCode = printResults("MacOsRobotSmoke", results)

    SwingUtilities.invokeLater {
        state.frame?.isVisible = false
        state.frame?.dispose()
    }

    return exitCode
}

// macOS silently drops Robot events when the JVM lacks Accessibility TCC — there's no API to
// query the permission, so probe by firing a click and observing whether the counter ticks. If
// it doesn't, return a remediation message for the caller to print and use as exit reason.
// The probe click counts toward `clickCount`, so the rig's scenarios capture their own deltas
// (this prime click is effectively the macOS analogue of the cold-JVM warmup click on Windows
// + an effective TCC check).
internal fun probeTccAccessibility(driver: RobotDriver, state: SmokeState): String? {
    val target = awtCenter(state, state.counterBounds)
    if (target == null) {
        return "TCC probe skipped: counter target rect not available; cannot infer permission state."
    }
    val before = state.clickCount
    driver.click(target.x, target.y)
    Thread.sleep(POST_CLICK_SETTLE_MS)
    val after = state.clickCount
    if (after > before) return null
    val javaHome = System.getProperty("java.home")
    return buildString {
        appendLine(
            "MacOsRobotSmoke aborting: probe click did NOT register (clickCount $before → $after)."
        )
        appendLine(
            "  This almost always means the JVM running this smoke lacks macOS Accessibility " +
                "permission (TCC), so java.awt.Robot input is being silently dropped."
        )
        appendLine("  JVM binary: $javaHome/bin/java")
        appendLine("  Remediation:")
        appendLine(
            "    1. Open System Settings → Privacy & Security → Accessibility, click +, and " +
                "add the JVM binary above (or the wrapping app — IntelliJ, Terminal — that " +
                "spawned it)."
        )
        appendLine(
            "    2. For the screenshot scenario (not exercised here), also add the JVM under " +
                "Privacy & Security → Screen Recording."
        )
        appendLine(
            "    3. Quit and re-launch Gradle / IntelliJ after granting; macOS only " +
                "re-evaluates TCC at process start."
        )
    }
}

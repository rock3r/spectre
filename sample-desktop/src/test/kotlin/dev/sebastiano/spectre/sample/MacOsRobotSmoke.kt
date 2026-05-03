@file:JvmName("MacOsRobotSmoke")

package dev.sebastiano.spectre.sample

import androidx.compose.ui.awt.ComposePanel
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Manual smoke for [RobotDriver] on macOS. Run via `./gradlew :sample-desktop:runMacOsRobotSmoke`.
 * Mirrors [WindowsRobotSmoke] / [LinuxRobotSmoke] — same seven scenarios via the shared
 * [runFocusedScenarios] rig — and reports PASS/FAIL per scenario.
 *
 * **TCC permission gate (Accessibility).** `java.awt.Robot.mousePress` on modern macOS requires the
 * wrapping process (Terminal, IntelliJ, Claude.app, etc.) to hold **Accessibility** permission
 * (System Settings → Privacy & Security → Accessibility), even for input into its own window —
 * macOS attributes Robot input to the user-visible parent process, not the forked JVM. Without the
 * grant, events are silently dropped. The smoke fires a probe click against the counter BEFORE
 * running the scenario suite; if `clickCount` doesn't increment it prints a remediation message
 * naming the wrapping app + JVM binary and exits with code 2.
 *
 * **TCC permission gate (Screen Recording).** `java.awt.Robot.createScreenCapture` (used by the 7th
 * scenario, [scenarioScreenshot]) needs **Screen Recording** — and on macOS 26 this is a *two-tier*
 * grant:
 *
 * 1. The toggle in System Settings → Privacy & Security → Screen & System Audio Recording grants
 *    *picker-based* access only (the system-mediated screen-capture picker UI).
 * 2. `createScreenCapture()` needs *direct* access. macOS prompts separately the first time the
 *    capture fires ("Claude is requesting to bypass the system private window picker" or similar
 *    for the relevant wrapping app); you must hit Allow on that dialog too.
 *
 * Until the direct-access grant is accepted, captures silently return an all-black image (no
 * exception). The screenshot scenario detects this by sampling the captured pixel and failing if
 * it's `#000000` — see [scenarioScreenshot] in the rig.
 *
 * **First-run flake on Screen Recording.** The bypass-picker dialog steals focus when it appears,
 * which would break any subsequent keystroke scenario. The rig orders the screenshot scenario
 * *last* in [runFocusedScenarios] so the prompt only impacts itself: on the first ever run with
 * Screen Recording denied, scenarios 1-6 pass and the screenshot fails opaquely-but-recoverably. A
 * second run after granting passes 7/7. Inherent Robot hazard, not a Spectre bug.
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
 * Findings from running this on macOS 26.4.1 / Temurin 21 (Retina 2× HiDPI):
 *
 * 1. **`alwaysOnTop` is NOT needed on macOS.** AppKit has no foreground-stealing-prevention
 *    analogous to Windows, so `toFront()` + `requestFocus()` unconditionally raises the window. The
 *    focused smoke runs with `isAlwaysOnTop = true` removed and reports 6/6 PASS. Kept off for
 *    symmetry with `MacOsRobotUnfocusedSmoke`, which never set it.
 * 2. **`CLIPBOARD_SETTLE_TIMEOUT_MS = 1_000L` holds on macOS.** `typeText` saw no NSPasteboard
 *    settle pressure on a developer Mac (cold JVM + normal load) — the budget tuned for
 *    NSPasteboard's worst-case latency was never approached during the focused-smoke run.
 * 3. **Cmd+S delivery via `pressKey(VK_S, META_DOWN_MASK)` works.** The shared rig's
 *    `scenarioShortcut` uses `shortcutModifierKeyCode(detectMacOs())` which returns `VK_META`
 *    (keyCode 157) on macOS, the modifier-mask path correctly translates to `META_DOWN_MASK`, and
 *    Compose's `onPreviewKeyEvent` handler (which accepts either `isCtrlPressed` or
 *    `isMetaPressed`) fires on the first attempt — `shortcutFiredCount 0 → 1`.
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

private fun runSmoke(): Int = runBlocking { runSmokeSuspend() }

private suspend fun runSmokeSuspend(): Int {
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
                // macOS does NOT need alwaysOnTop — AppKit has no foreground-stealing-prevention,
                // so toFront() + requestFocus() unconditionally raises the window. Confirmed
                // empirically: 6/6 PASS without isAlwaysOnTop on macOS 26.4.1 / Temurin 21.
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
    delay(POST_LAYOUT_WARMUP_MS.milliseconds)

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
internal suspend fun probeTccAccessibility(driver: RobotDriver, state: SmokeState): String? {
    val target = awtCenter(state, state.counterBounds)
    if (target == null) {
        return "TCC probe skipped: counter target rect not available; cannot infer permission state."
    }
    val before = state.clickCount
    driver.click(target.x, target.y)
    delay(POST_CLICK_SETTLE_MS.milliseconds)
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
        appendLine("  JVM binary (informational): $javaHome/bin/java")
        appendLine("  Remediation:")
        appendLine(
            "    1. Open System Settings → Privacy & Security → Accessibility and grant the " +
                "*wrapping app* — typically Terminal, iTerm2, IntelliJ, or Claude.app — not the " +
                "JVM binary itself. macOS attributes Robot input to the user-visible parent " +
                "process, not the forked JVM. When Gradle is launched via Claude Code's CLI the " +
                "chain is Claude.app → claude.app (CLI helper) → Gradle daemon → forked JVM, so " +
                "BOTH Claude.app entries need the grant."
        )
        appendLine(
            "    2. For the screenshot scenario (not exercised here), also grant Privacy & " +
                "Security → Screen Recording to the same wrapping app."
        )
        appendLine(
            "    3. Stop the Gradle daemon (`./gradlew --stop`) and any existing IDE/Terminal " +
                "processes after granting; macOS only re-evaluates TCC at process start, so an " +
                "already-running daemon keeps its old (denied) state until restarted."
        )
    }
}

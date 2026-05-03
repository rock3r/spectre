@file:JvmName("LinuxRobotSmoke")

package dev.sebastiano.spectre.sample

import androidx.compose.ui.awt.ComposePanel
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.Dimension
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual + CI smoke for [RobotDriver] on Linux. Run via `./gradlew
 * :sample-desktop:runLinuxRobotSmoke` (locally needs a working `DISPLAY`; CI runs it under
 * `xvfb-run` from `validation-linux.yml`). Mirrors [WindowsRobotSmoke] — same six scenarios via the
 * shared [runFocusedScenarios] rig — and reports PASS/FAIL per scenario.
 *
 * **Wayland gate.** Native Wayland forbids cross-process synthetic input, so `java.awt.Robot` only
 * works against X11 (real Xorg or XWayland-bridged X clients). When `XDG_SESSION_TYPE=wayland` AND
 * `DISPLAY` is unset (no XWayland fallback), the smoke aborts with exit code 2 before constructing
 * the JFrame — running the assertions under Wayland would silently produce 0/6 PASS because Robot
 * input is dropped at the protocol layer, not by the JVM. Same constraint Spectre's `LinuxX11Grab`
 * documents from #77.
 *
 * Findings from running this on Linux (placeholder — bank empirical results here on first real run,
 * mirroring the Windows smoke's findings list):
 *
 * 1. (TBD — Xvfb + KWin + Mutter foreground / focus behaviour with `isAlwaysOnTop`)
 * 2. (TBD — cold-JVM warmup click necessity under Xvfb)
 * 3. (TBD — clipboard-paste path on X11 vs the macOS NSPasteboard latency budget)
 *
 * What this *doesn't* exercise:
 * - Native Wayland input — gated above; not testable without a synthetic compositor that grants the
 *   JVM input-injection permission.
 * - HiDPI / fractional-scale Linux — out of scope for #20; covered (or to be covered) under the
 *   HiDPI-mapper validation tests instead.
 * - Jewel/IntelliJ tool-window text fields — `:sample-intellij-plugin`'s territory.
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

// Wayland forbids cross-process synthetic input; java.awt.Robot only works against X11. If we
// detect a pure-Wayland session with no XWayland fallback (DISPLAY unset), abort early with a
// clear remediation rather than running the scenarios and reporting 0/6 PASS for opaque
// reasons. Same gate Spectre's LinuxX11Grab uses from #77.
internal fun checkWaylandGate(): String? {
    val xdgSessionType = System.getenv("XDG_SESSION_TYPE")?.lowercase()
    val waylandDisplay = System.getenv("WAYLAND_DISPLAY")
    val xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR")
    val display = System.getenv("DISPLAY")
    val waylandSocketPresent =
        xdgRuntimeDir != null &&
            File(xdgRuntimeDir).listFiles()?.any { it.name.startsWith("wayland-") } == true
    val waylandIndicator =
        xdgSessionType == "wayland" || !waylandDisplay.isNullOrEmpty() || waylandSocketPresent
    if (waylandIndicator && display.isNullOrEmpty()) {
        return buildString {
            appendLine(
                "LinuxRobotSmoke aborting: pure-Wayland session detected and no XWayland fallback."
            )
            appendLine(
                "  XDG_SESSION_TYPE=$xdgSessionType WAYLAND_DISPLAY=$waylandDisplay " +
                    "wayland-socket-present=$waylandSocketPresent DISPLAY=$display"
            )
            appendLine(
                "  Wayland forbids cross-process synthetic input — java.awt.Robot drops events " +
                    "at the protocol layer, so running here would report 0/6 PASS."
            )
            appendLine(
                "  Remediation: run under Xorg (set DISPLAY to an X11 server, or use xvfb-run " +
                    "to start a virtual Xorg display)."
            )
        }
    }
    return null
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
            JFrame("Spectre Robot smoke (Linux)").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = composePanel
                pack()
                setLocationRelativeTo(null)
                // Keep alwaysOnTop on Linux too — KWin / Mutter / i3 honour it differently than
                // Windows but it never hurts, and on Xvfb (where focus stacking is minimal) it's
                // a no-op. First real-run findings should bank empirical WM behaviour here.
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

    printEnvironment("LinuxRobotSmoke", state)

    val driver = RobotDriver()
    warmupRobot(driver, state)
    val results = runFocusedScenarios(driver, state, typeTextExpected = "hello linux")
    val exitCode = printResults("LinuxRobotSmoke", results)

    SwingUtilities.invokeLater {
        state.frame?.isVisible = false
        state.frame?.dispose()
    }

    return exitCode
}

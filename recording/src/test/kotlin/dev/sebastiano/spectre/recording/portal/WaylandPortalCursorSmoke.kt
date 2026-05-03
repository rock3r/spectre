@file:JvmName("WaylandPortalCursorSmoke")

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Sibling of [WaylandPortalSmoke] for #87: verifies that the Wayland portal screen-cast renders the
 * compositor's cursor pixels into the captured mp4 when `RecordingOptions.captureCursor=true` (i.e.
 * the portal's `cursor_mode=Embedded`). The base smoke's animation never moves the mouse, so it
 * can't tell whether `Embedded` is honoured.
 *
 * **Manual mode is the default and the only mode that actually verifies cursor capture.** Mutter's
 * cursor sprite under Wayland does NOT track XTest synthetic warps — `Robot.mouseMove` updates the
 * X-server-side pointer position (so Java code sees the new coordinates) but the compositor's
 * cursor sprite stays where the physical pointer last was. That means a Robot-driven sweep can pass
 * the file-size gate while the recording contains zero cursor pixels. To get useful eyeball
 * evidence, the human runner has to wave their *physical* mouse across the JFrame during the
 * recording window. The script that the agent uses for #87 (`/tmp/spcrun.sh`) defaults the duration
 * to 8s for that reason.
 *
 * Set `SPECTRE_SMOKE_ROBOT=1` to force the Robot.mouseMove sweep instead — useful only as a
 * smoke-of-the-smoke (confirms the pipeline runs end-to-end), NOT as cursor-capture verification.
 *
 * Run via `./gradlew :recording:runWaylandPortalCursorSmoke` on a Wayland session — same setup as
 * [WaylandPortalSmoke] (compositor permission dialog on first run, `SPECTRE_WAYLAND_HELPER` env for
 * dev iteration). Verification is by eyeball — extract frames with `ffmpeg -y -i <mp4> -vf fps=4
 * /tmp/frame-%03d.png` and look for the pointer arrow.
 */
fun main() {
    var exitCode = 0
    try {
        runSmoke()
    } catch (t: Throwable) {
        System.err.println("Smoke failed: ${t.message}\n${t.stackTraceToString()}")
        exitCode = 1
    } finally {
        exitProcess(exitCode)
    }
}

private fun runSmoke() {
    val output =
        Path.of(System.getProperty("java.io.tmpdir"), "spectre-wayland-portal-cursor-smoke.mp4")
    Files.deleteIfExists(output)

    val durationMs =
        (System.getenv("SPECTRE_SMOKE_DURATION_MS")?.toLongOrNull() ?: DEFAULT_DURATION_MS).also {
            require(it >= MIN_DURATION_MS) {
                "SPECTRE_SMOKE_DURATION_MS must be >= $MIN_DURATION_MS, got $it"
            }
        }
    val useRobot = System.getenv("SPECTRE_SMOKE_ROBOT") == "1"

    val (frame, label) = openSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)

    val recorder = WaylandPortalRecorder()
    val frameBoundsAtStart = frame.bounds
    println(
        "(NOTE: a compositor permission dialog will pop on first run today; pick the monitor " +
            "with the JFrame and click \"Share.\" Subsequent runs reuse the grant silently.)"
    )
    if (useRobot) {
        println(
            "SPECTRE_SMOKE_ROBOT=1 — Robot.mouseMove sweep enabled. Note: under Wayland the " +
                "compositor's cursor sprite ignores XTest synthetic warps, so this confirms " +
                "the recording pipeline runs but cannot verify cursor pixels in the mp4."
        )
    } else {
        println(
            "Move your physical mouse back-and-forth across the JFrame during the " +
                "${durationMs / 1000}s recording window so the captured frames have a cursor " +
                "to verify. (Set SPECTRE_SMOKE_ROBOT=1 to enable the Robot.mouseMove sweep " +
                "instead — pipeline-only check, NOT cursor-capture verification.)"
        )
    }

    val handle =
        recorder.start(
            region = frameBoundsAtStart,
            output = output,
            options = RecordingOptions(frameRate = 30, captureCursor = true),
        )
    println("Recording started → $output (pid=${ProcessHandle.current().pid()})")

    val robot = if (useRobot) Robot() else null
    val sweepLeftX = frameBoundsAtStart.x + CURSOR_MARGIN
    val sweepRightX = frameBoundsAtStart.x + frameBoundsAtStart.width - CURSOR_MARGIN
    val sweepY = frameBoundsAtStart.y + frameBoundsAtStart.height / 2
    robot?.mouseMove(sweepLeftX, sweepY)

    var ticks = 0
    val animator =
        Thread.ofPlatform().daemon().name("spectre-wayland-portal-cursor-smoke-animator").start {
            val deadline = System.nanoTime() + durationMs * 1_000_000L
            val startNanos = System.nanoTime()
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                ticks += 1
                val current = ticks
                SwingUtilities.invokeLater { label.text = "tick=$current" }

                if (robot != null) {
                    val elapsed =
                        (System.nanoTime() - startNanos).toDouble() / (durationMs * 1_000_000L)
                    val clamped = elapsed.coerceIn(0.0, 1.0)
                    // Bounce left → right → left so mid-frame timestamps land away from the edges.
                    val phase = if (clamped < 0.5) clamped * 2 else (1 - clamped) * 2
                    val cursorX = sweepLeftX + (phase * (sweepRightX - sweepLeftX)).toInt()
                    robot.mouseMove(cursorX, sweepY)
                }

                Thread.sleep(ANIMATION_INTERVAL_MS.toLong())
            }
        }
    animator.join()
    println("Animation done — ticks fired: $ticks")

    handle.stop()
    val sizeBytes = if (Files.exists(output)) Files.size(output) else -1
    // Compute bytes/sec directly from milliseconds so sub-1s test durations don't truncate to 0.
    val perSecond = if (sizeBytes > 0) sizeBytes * 1_000L / durationMs else 0
    println("Recording stopped → $output ($sizeBytes bytes, ~$perSecond bytes/sec)")

    SwingUtilities.invokeLater {
        frame.isVisible = false
        frame.dispose()
    }

    // Same shape: derive the byte-floor straight from durationMs so a sub-1s smoke run
    // doesn't silently zero out the gate (which would let any size pass).
    val threshold = BLACK_FRAME_PER_SECOND_THRESHOLD * durationMs / 1_000L
    if (sizeBytes < threshold) {
        println(
            "FAIL — file size $sizeBytes is below the black-frame threshold ($threshold). " +
                "Same diagnostic as the base smoke applies (FD inheritance / encoder fed no " +
                "frames). Inspect a frame with PIL to confirm."
        )
        error("cursor smoke produced suspiciously small mp4 ($sizeBytes bytes)")
    }
    println(
        "PASS — file size is plausible. Eyeball $output (or extract frames with " +
            "`ffmpeg -y -i $output -vf fps=4 /tmp/spectre-cursor-frame-%03d.png`) to confirm " +
            "the pointer arrow is visible. A working Embedded capture shows the system cursor " +
            "wherever the human moved it; a broken one shows the JFrame content but no cursor."
    )
}

private fun openSmokeWindow(): Pair<JFrame, JLabel> {
    val ready = CountDownLatch(1)
    var frameRef: JFrame? = null
    var labelRef: JLabel? = null
    SwingUtilities.invokeLater {
        val label =
            JLabel("tick=0", SwingConstants.CENTER).apply {
                font = Font(Font.SANS_SERIF, Font.BOLD, LABEL_FONT_SIZE)
                foreground = Color.BLACK
            }
        val panel =
            JPanel(BorderLayout()).apply {
                background = Color.WHITE
                isOpaque = true
                add(label, BorderLayout.CENTER)
            }
        val frame =
            JFrame("Spectre Wayland portal cursor smoke").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = panel
                preferredSize = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
                pack()
                setLocationRelativeTo(null)
                isVisible = true
                toFront()
                requestFocus()
            }
        frameRef = frame
        labelRef = label
        ready.countDown()
    }
    check(ready.await(WINDOW_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "JFrame never came up within ${WINDOW_OPEN_TIMEOUT_SECONDS}s"
    }
    return frameRef!! to labelRef!!
}

private const val BLACK_FRAME_PER_SECOND_THRESHOLD: Long = 4_000

// Floor on SPECTRE_SMOKE_DURATION_MS — anything shorter doesn't produce enough
// frames for the size-based gate to be meaningful, and would also push the
// human-driven cursor wave into impossible territory.
private const val MIN_DURATION_MS = 500L

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 240
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_OPEN_TIMEOUT_SECONDS = 5L
// Default capture window is generous because the human has to physically wave the mouse
// across the JFrame during the recording — the Robot.mouseMove path is a false negative
// on Wayland (Mutter's cursor sprite ignores XTest synthetic warps).
private const val DEFAULT_DURATION_MS = 8_000L
private const val ANIMATION_INTERVAL_MS = 50
private const val LABEL_FONT_SIZE = 48
private const val CURSOR_MARGIN = 20

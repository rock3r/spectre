@file:JvmName("WaylandPortalWindowSmoke")

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
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
 * Manual end-to-end smoke for the window-targeted Wayland recording path (#85). Boots a JFrame,
 * starts a [WaylandPortalWindowRecorder] capture (SourceType.WINDOW; the user picks the window at
 * the portal dialog), then animates a label and **moves the JFrame around the screen** during the
 * recording so the resulting mp4 demonstrates the compositor's auto-follow behaviour.
 *
 * A working Window source-type capture should:
 *
 * - Produce an mp4 whose dimensions match the JFrame's pixel size, NOT the monitor size.
 * - Keep the JFrame contents visible across the move animation — the recording follows the window
 *   across the screen instead of capturing whatever desktop region the window started in.
 * - Show no stretched/clipped frames at the corners of the move path (the region-targeted recorder
 *   would either clip or leak desktop pixels).
 *
 * Run via `./gradlew :recording:runWaylandPortalWindowSmoke` on a Wayland session — same setup as
 * [WaylandPortalSmoke] (compositor permission dialog on first run, `SPECTRE_WAYLAND_HELPER` env for
 * dev iteration). When the dialog pops, **pick the JFrame from the window picker** (not a monitor —
 * the dialog should default to a window picker on this source type).
 *
 * Verification is by eyeball:
 * - `ffprobe <mp4>` to confirm dimensions = JFrame's pixel size.
 * - `ffmpeg -y -i <mp4> -vf fps=2 /tmp/spectre-window-frame-%03d.png` and look at the frames: the
 *   JFrame's "tick=N" label should be visible in every frame, regardless of where on the desktop
 *   the window was at that moment.
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
        Path.of(System.getProperty("java.io.tmpdir"), "spectre-wayland-portal-window-smoke.mp4")
    Files.deleteIfExists(output)

    val durationMs =
        System.getenv("SPECTRE_SMOKE_DURATION_MS")?.toLongOrNull() ?: DEFAULT_DURATION_MS
    require(durationMs >= MIN_DURATION_MS) {
        "SPECTRE_SMOKE_DURATION_MS must be >= $MIN_DURATION_MS, got $durationMs"
    }

    val (frame, label) = openSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)

    val recorder = WaylandPortalWindowRecorder()
    println(
        "(NOTE: a compositor permission dialog will pop on first run today; pick the JFrame " +
            "in the WINDOW picker and click \"Share.\" Subsequent runs reuse the grant silently.)"
    )
    println(
        "The smoke will animate the JFrame's position across the screen during the " +
            "${durationMs / 1000}s recording. The mp4 should follow the window — keep its " +
            "label visible in every frame regardless of where on the desktop the window is."
    )

    val handle =
        recorder.start(
            window = frame.asTitledWindow(),
            output = output,
            options = RecordingOptions(frameRate = 30, captureCursor = true),
        )
    println("Recording started → $output (pid=${ProcessHandle.current().pid()})")

    val initialLocation = frame.location
    val animator =
        Thread.ofPlatform().daemon().name("spectre-wayland-portal-window-smoke-animator").start {
            val deadline = System.nanoTime() + durationMs * 1_000_000L
            val startNanos = System.nanoTime()
            var ticks = 0
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                ticks += 1
                val tickNum = ticks
                val elapsed =
                    (System.nanoTime() - startNanos).toDouble() / (durationMs * 1_000_000L)
                val clamped = elapsed.coerceIn(0.0, 1.0)
                // Bounce the window across a horizontal range and through a quarter-cycle of
                // vertical motion. Mutter on Ubuntu honours XWayland window-move requests, so
                // even from ssh this drives a real compositor move.
                val phaseX = if (clamped < 0.5) clamped * 2 else (1 - clamped) * 2
                val targetX = initialLocation.x + (phaseX * MOVE_RANGE_X).toInt() - MOVE_RANGE_X / 2
                val targetY =
                    initialLocation.y + (kotlin.math.sin(clamped * Math.PI) * MOVE_RANGE_Y).toInt()
                SwingUtilities.invokeLater {
                    label.text = "tick=$tickNum"
                    frame.location = Point(targetX, targetY)
                }
                Thread.sleep(ANIMATION_INTERVAL_MS.toLong())
            }
            println("Animation done — ticks fired: $ticks")
        }
    animator.join()

    handle.stop()
    val sizeBytes = if (Files.exists(output)) Files.size(output) else -1
    val perSecond = if (sizeBytes > 0) sizeBytes * 1_000L / durationMs else 0
    println("Recording stopped → $output ($sizeBytes bytes, ~$perSecond bytes/sec)")

    SwingUtilities.invokeLater {
        frame.isVisible = false
        frame.dispose()
    }

    val threshold = BLACK_FRAME_PER_SECOND_THRESHOLD * durationMs / 1_000L
    if (sizeBytes < threshold) {
        println(
            "FAIL — file size $sizeBytes is below the black-frame threshold ($threshold). " +
                "Same diagnostic as the base smoke applies (FD inheritance / encoder fed no " +
                "frames, or the user picked the wrong source at the portal dialog)."
        )
        error("window smoke produced suspiciously small mp4 ($sizeBytes bytes)")
    }
    println(
        "PASS — file size is plausible. Eyeball $output (or extract frames with " +
            "`ffmpeg -y -i $output -vf fps=2 /tmp/spectre-window-frame-%03d.png`) to confirm " +
            "the JFrame contents follow across the move animation. The mp4 dimensions should " +
            "match the JFrame's pixel size (run `ffprobe $output` to check)."
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
            JFrame("Spectre Wayland portal window smoke").apply {
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

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 240
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_OPEN_TIMEOUT_SECONDS = 5L
private const val DEFAULT_DURATION_MS = 6_000L
private const val MIN_DURATION_MS = 500L
private const val ANIMATION_INTERVAL_MS = 60
private const val LABEL_FONT_SIZE = 48
private const val MOVE_RANGE_X = 600
private const val MOVE_RANGE_Y = 200

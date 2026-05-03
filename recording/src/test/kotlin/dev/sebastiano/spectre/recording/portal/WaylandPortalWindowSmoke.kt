@file:JvmName("WaylandPortalWindowSmoke")

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
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
 * Manual end-to-end smoke for the Wayland portal window-targeted recording path (#85). Boots a
 * JFrame, starts a [WaylandPortalWindowRecorder] capture against the JFrame's bounds
 * (`SourceType.WINDOW` — the user picks the JFrame at the portal's window picker), records for ~3
 * seconds, prints the resulting file path + size + bytes/sec.
 *
 * Counterpart to [WaylandPortalSmoke] (region capture, `SourceType.MONITOR`); same shape, the
 * difference is the source type and that the user picks a window rather than a monitor at the
 * dialog. Both produce a window-sized mp4 from the helper's existing crop. Window-source-type's win
 * is that **only the picked window's pixels** are in the granted PipeWire stream — anything
 * floating above the window during the recording does not appear, unlike the region path.
 *
 * For dev iteration without rebuilding the helper jar resource, set `SPECTRE_WAYLAND_HELPER` to a
 * locally-built helper path. See the base smoke's KDoc for the env-var mechanics.
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

    val (frame, label) = openSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)

    val recorder = WaylandPortalWindowRecorder()
    val frameBoundsAtStart = frame.bounds
    println(
        "(NOTE: a compositor permission dialog will pop on first run today; pick the JFrame " +
            "in the WINDOW picker and click \"Share.\" Subsequent runs reuse the grant silently.)"
    )

    val handle =
        recorder.start(
            window = frame.asTitledWindow(),
            region = frameBoundsAtStart,
            output = output,
            options = RecordingOptions(frameRate = 30, captureCursor = true),
        )
    println("Recording started → $output (pid=${ProcessHandle.current().pid()})")

    var ticks = 0
    val animator =
        Thread.ofPlatform().daemon().name("spectre-wayland-portal-window-smoke-animator").start {
            val deadline = System.nanoTime() + RECORD_DURATION_MS * 1_000_000L
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                ticks += 1
                val current = ticks
                SwingUtilities.invokeLater { label.text = "tick=$current" }
                Thread.sleep(ANIMATION_INTERVAL_MS.toLong())
            }
        }
    animator.join()
    println("Animation done — ticks fired: $ticks")

    handle.stop()
    val sizeBytes = if (Files.exists(output)) Files.size(output) else -1
    val perSecond = if (sizeBytes > 0) sizeBytes / (RECORD_DURATION_MS / 1000) else 0
    println("Recording stopped → $output ($sizeBytes bytes, ~$perSecond bytes/sec)")

    SwingUtilities.invokeLater {
        frame.isVisible = false
        frame.dispose()
    }

    val threshold = BLACK_FRAME_PER_SECOND_THRESHOLD * (RECORD_DURATION_MS / 1000)
    if (sizeBytes < threshold) {
        println(
            "FAIL — file size $sizeBytes is below the black-frame threshold ($threshold). " +
                "Diagnostic: same as the base smoke (FD inheritance, encoder fed no frames, " +
                "user picked the wrong source at the portal dialog)."
        )
        error("window smoke produced suspiciously small mp4 ($sizeBytes bytes)")
    }
    println(
        "PASS — file size is plausible. Eyeball $output to confirm: dimensions match the JFrame's " +
            "pixel size (run `ffprobe $output`), and the visible content is the JFrame with " +
            "no other apps' pixels around it."
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
private const val RECORD_DURATION_MS = 3_000L
private const val ANIMATION_INTERVAL_MS = 50
private const val LABEL_FONT_SIZE = 48

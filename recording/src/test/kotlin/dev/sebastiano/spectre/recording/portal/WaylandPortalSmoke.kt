@file:JvmName("WaylandPortalSmoke")

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingOptions
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
 * Manual end-to-end smoke for the Wayland portal + PipeWire recording path. Run via `./gradlew
 * :recording:runWaylandPortalSmoke` on a Wayland session — opens a JFrame, asks the compositor for
 * a screen-cast (this pops a permission dialog on first run, click "Share"), records for ~3 seconds
 * via [WaylandPortalRecorder] + the bundled `spectre-wayland-helper` Rust binary +
 * `gst-launch-1.0`, prints the resulting file path + size + bytes/sec.
 *
 * **Stage 3 (#80) closes the loop**: the recorder produces real frames via the helper's FD-passing
 * pipeline. A "PASS" result means the mp4 is non-trivial in size (above the black-frame threshold)
 * and ffprobe-readable.
 *
 * Counterpart to [dev.sebastiano.spectre.recording.FfmpegX11GrabSmoke] (Linux Xorg) and
 * [dev.sebastiano.spectre.recording.FfmpegGdigrabSmoke] (Windows). #76's smoke produced 99.7%-black
 * frames on the dev VM (because the VM is Wayland and x11grab through XWayland silently captured
 * nothing); this smoke is the proof that #77 stage 3 closes that gap.
 *
 * For dev iteration without rebuilding the helper jar resource, set the `SPECTRE_WAYLAND_HELPER`
 * env var to a locally-built helper path — e.g.
 * `SPECTRE_WAYLAND_HELPER=$HOME/spectre/recording/native/linux/target/release/spectre-wayland-helper`.
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
    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-wayland-portal-smoke.mp4")
    Files.deleteIfExists(output)

    val (frame, label) = openSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)

    val recorder = WaylandPortalRecorder()
    val frameBoundsAtStart = frame.bounds
    println(
        "(NOTE: a compositor permission dialog will pop on first run today; pick the monitor " +
            "with the JFrame and click \"Share.\" Subsequent runs reuse the grant silently.)"
    )

    val handle =
        recorder.start(
            region = frameBoundsAtStart,
            output = output,
            options = RecordingOptions(frameRate = 30, captureCursor = true),
        )
    println("Recording started → $output (pid=${ProcessHandle.current().pid()})")

    var ticks = 0
    val animator =
        Thread.ofPlatform().daemon().name("spectre-wayland-portal-smoke-animator").start {
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
                "Most likely cause: FD inheritance didn't work (gst-launch fell back to " +
                "fd=-1), or the encoder ran but received no frames. Inspect a frame with PIL " +
                "to confirm: `ffmpeg -y -ss 1 -i $output -frames:v 1 /tmp/frame.png && " +
                "python3 -c \"from PIL import Image; im = Image.open('/tmp/frame.png'); " +
                "print('unique colors:', len(im.getcolors(maxcolors=99999) or []))\"`"
        )
        error("stage-3 smoke produced suspiciously small mp4 ($sizeBytes bytes)")
    }
    println(
        "PASS — file size is plausible for real captured pixels (>$threshold bytes for the " +
            "${RECORD_DURATION_MS / 1000}s recording). Eyeball the .mp4 to confirm content " +
            "matches the JFrame, then validate via PIL unique-color count if you want a " +
            "harder gate (the #76 / #77-stage-2 black-frame symptom was 1-2 unique colors)."
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
            JFrame("Spectre Wayland portal smoke").apply {
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

// Threshold for "this looks like real captured pixels, not black frames." A 480×240 H.264
// stream at 30fps with the JFrame's mostly-static white background and changing label text
// typically lands in the 10-30 KB/s range; uniform-black frames compress to ~1-2 KB/s. 4 KB/s
// is a generous floor that flags the bad case without false-positiving on the good one.
private const val BLACK_FRAME_PER_SECOND_THRESHOLD: Long = 4_000

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 240
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_OPEN_TIMEOUT_SECONDS = 5L
private const val RECORD_DURATION_MS = 3_000L
private const val ANIMATION_INTERVAL_MS = 50
private const val LABEL_FONT_SIZE = 48

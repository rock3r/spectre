@file:JvmName("ScreenCaptureKitRecorderSmoke")

package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual end-to-end smoke for [ScreenCaptureKitRecorder]. Run via `./gradlew
 * :recording:runScreenCaptureKitSmoke` — opens a small JFrame, records it for ~3 seconds, prints
 * the resulting file path + size. Eyeball the .mov to confirm.
 *
 * Uses a plain Swing JFrame rather than Compose so this can live in the recording module without
 * breaking its dependency isolation. The label is updated via `JLabel.setText` rather than a custom
 * `paintComponent` override — empirically (see commit log), `paintComponent` overrides on a
 * freshly-shown JFrame on JBR 21 / macOS 26 sometimes never fire, leaving the window blank inside.
 */
fun main() {
    var exitCode = 0
    try {
        runSmoke()
    } catch (t: Throwable) {
        // Print full stack as a single multiline string rather than .printStackTrace() so the
        // detekt PrintStackTrace rule isn't tripped — this is a manual smoke entry point, not
        // production code, but the rule fires regardless.
        System.err.println("Smoke failed: ${t.message}\n${t.stackTraceToString()}")
        exitCode = 1
    } finally {
        exitProcess(exitCode)
    }
}

private fun runSmoke() {
    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-sck-smoke.mov")
    val midRecordingPng = Path.of(System.getProperty("java.io.tmpdir"), "spectre-sck-smoke.png")
    Files.deleteIfExists(output)
    Files.deleteIfExists(midRecordingPng)

    val (frame, label) = openSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)

    val recorder = ScreenCaptureKitRecorder()
    val handle =
        recorder.start(
            window = frame.asTitledWindow(),
            output = output,
            options = RecordingOptions(frameRate = 30, captureCursor = true),
        )

    println("Recording started → $output (pid=${ProcessHandle.current().pid()})")

    var ticks = 0
    val animator =
        Thread.ofPlatform().daemon().name("spectre-smoke-animator").start {
            val deadline = System.nanoTime() + RECORD_DURATION_MS * 1_000_000L
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                ticks += 1
                val current = ticks
                SwingUtilities.invokeLater { label.text = "tick=$current" }
                Thread.sleep(ANIMATION_INTERVAL_MS.toLong())
            }
        }

    // Halfway through the recording, take a JVM-side `Robot` screenshot of the JFrame's bounds.
    // If THAT shows the tick text, Swing is painting and any blankness in the .mov is on the
    // SCK side. If it does NOT, Swing isn't painting and we have a UI bug to chase.
    Thread.sleep(RECORD_DURATION_MS / 2)
    val frameBounds = frame.bounds
    val robotShot = Robot().createScreenCapture(frameBounds)
    ImageIO.write(robotShot, "png", File(midRecordingPng.toString()))
    println("Robot screenshot of JFrame bounds $frameBounds → $midRecordingPng")

    animator.join()
    println("Animation done — ticks fired: $ticks")

    handle.stop()
    val sizeBytes = if (Files.exists(output)) Files.size(output) else -1
    println("Recording stopped → $output (${sizeBytes} bytes)")

    SwingUtilities.invokeLater {
        frame.isVisible = false
        frame.dispose()
    }
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
            JFrame("Spectre SCK smoke").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = panel
                preferredSize = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
                pack()
                setLocationRelativeTo(null)
                isVisible = true
                // Force the window to come to front on macOS — recordings of a window that
                // never got first-paint focus can produce a blank backing store.
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

/** Adapt a [java.awt.Frame] to the [TitledWindow] surface the recorder expects. */
private fun Frame.asTitledWindow(): TitledWindow =
    object : TitledWindow {
        override var title: String?
            get() = this@asTitledWindow.title
            set(value) {
                this@asTitledWindow.title = value ?: ""
            }
    }

@Suppress("unused") private fun Rectangle.toString(): String = "Rectangle($x,$y ${width}x$height)"

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 240
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_OPEN_TIMEOUT_SECONDS = 5L
private const val RECORD_DURATION_MS = 3_000L
private const val ANIMATION_INTERVAL_MS = 50
private const val LABEL_FONT_SIZE = 48

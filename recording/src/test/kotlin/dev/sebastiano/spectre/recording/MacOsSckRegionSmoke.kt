@file:JvmName("MacOsSckRegionSmoke")

package dev.sebastiano.spectre.recording

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
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
 * Manual macOS-only smoke for SCK-backed region recording through [AutoRecorder]. Run via
 * `./gradlew :recording:runMacOsSckRegionSmoke`.
 */
fun main() {
    var exitCode = 0
    try {
        runRegionSmoke()
    } catch (t: Throwable) {
        System.err.println("Smoke failed: ${t.message}\n${t.stackTraceToString()}")
        exitCode = 1
    } finally {
        exitProcess(exitCode)
    }
}

private fun runRegionSmoke() {
    check(System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        "ScreenCaptureKit region smoke only runs on macOS."
    }

    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-sck-region-smoke.mov")
    val midRecordingPng =
        Path.of(System.getProperty("java.io.tmpdir"), "spectre-sck-region-smoke.png")
    Files.deleteIfExists(output)
    Files.deleteIfExists(midRecordingPng)

    val (frame, label) = openRegionSmokeWindow()
    try {
        Thread.sleep(WINDOW_SETTLE_MS)
        val region = runOnEdt {
            frame.toFront()
            frame.requestFocus()
            val location = frame.locationOnScreen
            Rectangle(location.x, location.y, frame.width, frame.height)
        }

        val handle =
            AutoRecorder()
                .startRegion(
                    region = region,
                    output = output,
                    options = RecordingOptions(frameRate = 30, captureCursor = true),
                )

        println("SCK region recording started for $region -> $output")
        var ticks = 0
        val animator =
            Thread.ofPlatform().daemon().name("spectre-sck-region-animator").start {
                val deadline = System.nanoTime() + RECORD_DURATION_MS * 1_000_000L
                while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                    ticks += 1
                    val current = ticks
                    SwingUtilities.invokeLater { label.text = "region tick=$current" }
                    Thread.sleep(ANIMATION_INTERVAL_MS.toLong())
                }
            }

        Thread.sleep(RECORD_DURATION_MS / 2)
        val robotShot = Robot().createScreenCapture(region)
        ImageIO.write(robotShot, "png", File(midRecordingPng.toString()))
        println("Robot screenshot of recorded region $region -> $midRecordingPng")

        animator.join()
        handle.stop()

        val bytes = Files.size(output)
        check(bytes > MINIMUM_RECORDING_BYTES) {
            "Expected a non-empty MOV larger than $MINIMUM_RECORDING_BYTES bytes, got $bytes at $output"
        }
        println("SCK region recording stopped -> $output ($bytes bytes, ticks=$ticks)")
    } finally {
        SwingUtilities.invokeLater {
            frame.isVisible = false
            frame.dispose()
        }
    }
}

private fun openRegionSmokeWindow(): Pair<JFrame, JLabel> {
    val ready = CountDownLatch(1)
    var frameRef: JFrame? = null
    var labelRef: JLabel? = null
    SwingUtilities.invokeLater {
        val label =
            JLabel("region tick=0", SwingConstants.CENTER).apply {
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
            JFrame("Spectre SCK region smoke").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane = panel
                preferredSize = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
                pack()
                location = Point(WINDOW_X, WINDOW_Y)
                isAlwaysOnTop = true
                isVisible = true
                toFront()
                requestFocus()
            }
        frameRef = frame
        labelRef = label
        ready.countDown()
    }
    check(ready.await(WINDOW_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Timed out opening SCK region smoke window"
    }
    return checkNotNull(frameRef) to checkNotNull(labelRef)
}

private fun <T> runOnEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}

private const val WINDOW_WIDTH = 460
private const val WINDOW_HEIGHT = 220
private const val WINDOW_X = 120
private const val WINDOW_Y = 140
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_READY_TIMEOUT_SECONDS = 5L
private const val RECORD_DURATION_MS = 3_000L
private const val ANIMATION_INTERVAL_MS = 50
private const val LABEL_FONT_SIZE = 36
private const val MINIMUM_RECORDING_BYTES = 10_000L

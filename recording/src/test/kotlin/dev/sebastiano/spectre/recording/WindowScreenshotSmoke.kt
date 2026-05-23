@file:JvmName("WindowScreenshotSmoke")

package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.FfmpegBackend.Companion.detectWaylandSession
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
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
 * Manual end-to-end smoke for [AutoScreenshotter]. Run via `./gradlew
 * :recording:runWindowScreenshotSmoke`.
 *
 * Expected behavior:
 * - macOS: captures a PNG through ScreenCaptureKit (`spectre-screencapture --mode screenshot`).
 * - Windows: captures a PNG through the Windows Graphics Capture helper.
 * - Linux X11: captures a PNG through ffmpeg `x11grab` region fallback.
 * - Linux Wayland: verifies the current still-screenshot unsupported error and exits successfully.
 *   Use `runWaylandPortalWindowSmoke` for the Wayland window-targeted video path.
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
        Path.of(System.getProperty("java.io.tmpdir"), "spectre-window-screenshot-smoke.png")
    Files.deleteIfExists(output)

    val (frame, label) = openSmokeWindow()
    try {
        Thread.sleep(WINDOW_SETTLE_MS)
        SwingUtilities.invokeAndWait {
            frame.toFront()
            frame.requestFocus()
            label.text = "screenshot smoke"
        }
        Thread.sleep(WINDOW_SETTLE_MS)

        val screenshotter = AutoScreenshotter()
        if (isWaylandSession()) {
            val error =
                runCatching { screenshotter.captureWindow(frame.asTitledWindow()) }
                    .exceptionOrNull()
            check(error is UnsupportedOperationException) {
                "Expected Wayland still screenshot to be unsupported, got $error"
            }
            check(error.message.orEmpty().contains("Wayland")) {
                "Expected Wayland guidance in unsupported message, got: ${error.message}"
            }
            println(
                "Wayland still screenshot unsupported as expected; use " +
                    "`./gradlew :recording:runWaylandPortalWindowSmoke` for window video."
            )
            return
        }

        val image = screenshotter.captureWindow(frame.asTitledWindow())
        check(image.width > 1 && image.height > 1) {
            "Captured image has invalid dimensions: ${image.width}x${image.height}"
        }
        check(ImageIO.write(image, "png", output.toFile())) {
            "ImageIO could not write PNG to $output"
        }
        val size = Files.size(output)
        check(size > 0) { "Screenshot output was empty: $output" }
        println("Window screenshot smoke captured ${image.width}x${image.height} PNG → $output")
    } finally {
        SwingUtilities.invokeLater {
            frame.isVisible = false
            frame.dispose()
        }
    }
}

private fun openSmokeWindow(): Pair<JFrame, JLabel> {
    val ready = CountDownLatch(1)
    var frameRef: JFrame? = null
    var labelRef: JLabel? = null
    SwingUtilities.invokeLater {
        val label =
            JLabel("starting", SwingConstants.CENTER).apply {
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
            JFrame("Spectre screenshot smoke").apply {
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
    check(ready.await(WINDOW_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Timed out opening screenshot smoke window"
    }
    return checkNotNull(frameRef) to checkNotNull(labelRef)
}

private fun isWaylandSession(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("linux") &&
        detectWaylandSession(System::getenv)

private const val WINDOW_WIDTH: Int = 360
private const val WINDOW_HEIGHT: Int = 180
private const val LABEL_FONT_SIZE: Int = 28
private const val WINDOW_SETTLE_MS: Long = 500
private const val WINDOW_READY_TIMEOUT_SECONDS: Long = 5

@file:JvmName("WindowsGraphicsCaptureRegionSmoke")

package dev.sebastiano.spectre.recording

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual Windows-only smoke for WGC-backed region recording. Run via `./gradlew
 * :recording:runWindowsGraphicsCaptureRegionSmoke`.
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
    check(System.getProperty("os.name").orEmpty().lowercase().contains("windows")) {
        "Windows Graphics Capture region smoke only runs on Windows."
    }

    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-wgc-region-smoke.mp4")
    Files.deleteIfExists(output)

    val (frame, button, colorPanel) = openRegionSmokeWindow()
    try {
        val region = runOnEdt {
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
        try {
            Thread.sleep(RECORDING_WARMUP_MS)
            click(button)
            waitUntil("button input changed the panel color") {
                runOnEdt { colorPanel.background == CAPTURED_COLOR }
            }
            Thread.sleep(RECORDING_AFTER_INPUT_MS)
        } finally {
            handle.stop()
        }

        val bytes = Files.size(output)
        check(bytes > MINIMUM_RECORDING_BYTES) {
            "Expected a non-empty MP4 larger than $MINIMUM_RECORDING_BYTES bytes, got $bytes at $output"
        }
        println("Windows Graphics Capture region smoke wrote $bytes bytes -> $output")
    } finally {
        SwingUtilities.invokeLater {
            frame.isVisible = false
            frame.dispose()
        }
    }
}

private fun openRegionSmokeWindow(): Triple<JFrame, JButton, JPanel> {
    val ready = CountDownLatch(1)
    var frameRef: JFrame? = null
    var buttonRef: JButton? = null
    var colorPanelRef: JPanel? = null
    SwingUtilities.invokeLater {
        val colorPanel =
            JPanel(BorderLayout()).apply {
                background = STARTING_COLOR
                isOpaque = true
            }
        val panel =
            JPanel(BorderLayout()).apply {
                background = STARTING_COLOR
                isOpaque = true
            }
        val button =
            JButton("region-ready").apply {
                addActionListener {
                    colorPanel.background = CAPTURED_COLOR
                    colorPanel.repaint()
                }
            }
        panel.add(colorPanel, BorderLayout.CENTER)
        panel.add(button, BorderLayout.SOUTH)
        val frame =
            JFrame("Spectre WGC region smoke").apply {
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
        buttonRef = button
        colorPanelRef = colorPanel
        ready.countDown()
    }
    check(ready.await(WINDOW_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Timed out opening WGC region smoke window"
    }
    return Triple(checkNotNull(frameRef), checkNotNull(buttonRef), checkNotNull(colorPanelRef))
}

private fun click(button: JButton) {
    SwingUtilities.invokeAndWait { button.doClick() }
}

private fun <T> runOnEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}

private fun waitUntil(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_TIMEOUT_SECONDS)
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(WAIT_POLL_MS)
    }
    error("Timed out waiting for $description")
}

private const val WINDOW_WIDTH: Int = 360
private const val WINDOW_HEIGHT: Int = 180
private const val WINDOW_X: Int = 160
private const val WINDOW_Y: Int = 160
private const val RECORDING_WARMUP_MS: Long = 600
private const val RECORDING_AFTER_INPUT_MS: Long = 1_200
private const val WINDOW_READY_TIMEOUT_SECONDS: Long = 5
private const val WAIT_TIMEOUT_SECONDS: Long = 5
private const val WAIT_POLL_MS: Long = 50
private const val MINIMUM_RECORDING_BYTES: Long = 8_192

private val STARTING_COLOR = Color(0xF7, 0xF7, 0xF7)
private val CAPTURED_COLOR = Color(0x20, 0xC9, 0x97)

@file:JvmName("WindowsGraphicsCaptureMoveInputSmoke")

package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual Windows-only end-to-end smoke for the Windows Graphics Capture still-image path. Run via
 * `./gradlew :recording:runWindowsGraphicsCaptureMoveInputSmoke`.
 *
 * The smoke moves a JFrame, drives real Robot input into the moved window, captures it through
 * [AutoScreenshotter], and asserts the PNG contains the input-driven visual state. It is a focused
 * sanity check that WGC is following the window source rather than a stale screen rectangle.
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
    check(System.getProperty("os.name").orEmpty().lowercase().contains("windows")) {
        "Windows Graphics Capture move/input smoke only runs on Windows."
    }

    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-wgc-move-input-smoke.png")
    Files.deleteIfExists(output)

    val (frame, button, colorPanel) = openSmokeWindow()
    try {
        SwingUtilities.invokeAndWait {
            frame.isAlwaysOnTop = true
            frame.location = Point(MOVED_WINDOW_X, MOVED_WINDOW_Y)
            frame.toFront()
            frame.requestFocus()
        }
        Thread.sleep(WINDOW_SETTLE_MS)

        click(button)
        waitUntil("button input changed the panel color") {
            runOnEdt { colorPanel.background == CAPTURED_COLOR }
        }

        val image = AutoScreenshotter().captureWindow(frame.asTitledWindow())
        check(image.width > 1 && image.height > 1) {
            "Captured image has invalid dimensions: ${image.width}x${image.height}"
        }
        val sample = runOnEdt {
            val windowLocation = frame.locationOnScreen
            val panelLocation = colorPanel.locationOnScreen
            Point(
                panelLocation.x - windowLocation.x + colorPanel.width / 2,
                panelLocation.y - windowLocation.y + colorPanel.height / 2,
            )
        }
        val center =
            image.getRGB(
                sample.x.coerceIn(0, image.width - 1),
                sample.y.coerceIn(0, image.height - 1),
            )
        check(colorsClose(center, CAPTURED_COLOR.rgb)) {
            "Expected captured center pixel to be close to $CAPTURED_COLOR, got " +
                Color(center, false)
        }
        check(ImageIO.write(image, "png", output.toFile())) {
            "ImageIO could not write PNG to $output"
        }
        println(
            "Windows Graphics Capture move/input smoke captured " +
                "${image.width}x${image.height} PNG -> $output"
        )
    } finally {
        SwingUtilities.invokeLater {
            frame.isVisible = false
            frame.dispose()
        }
    }
}

private fun openSmokeWindow(): Triple<JFrame, JButton, JPanel> {
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
            JButton("capture-ready").apply {
                addActionListener {
                    colorPanel.background = CAPTURED_COLOR
                    colorPanel.repaint()
                }
            }
        panel.add(colorPanel, BorderLayout.CENTER)
        panel.add(button, BorderLayout.SOUTH)
        val frame =
            JFrame("Spectre WGC move input smoke").apply {
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
        "Timed out opening WGC move/input smoke window"
    }
    return Triple(checkNotNull(frameRef), checkNotNull(buttonRef), checkNotNull(colorPanelRef))
}

private fun click(button: JButton) {
    val location = runOnEdt { button.locationOnScreen }
    val size = runOnEdt { button.size }
    val robot =
        Robot().apply {
            autoDelay = ROBOT_AUTO_DELAY_MS
            isAutoWaitForIdle = true
        }
    robot.mouseMove(location.x + size.width / 2, location.y + size.height / 2)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
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

private fun colorsClose(actual: Int, expected: Int): Boolean {
    val actualColor = Color(actual, false)
    val expectedColor = Color(expected, false)
    return kotlin.math.abs(actualColor.red - expectedColor.red) <= COLOR_TOLERANCE &&
        kotlin.math.abs(actualColor.green - expectedColor.green) <= COLOR_TOLERANCE &&
        kotlin.math.abs(actualColor.blue - expectedColor.blue) <= COLOR_TOLERANCE
}

private const val WINDOW_WIDTH: Int = 360
private const val WINDOW_HEIGHT: Int = 180
private const val WINDOW_X: Int = 120
private const val WINDOW_Y: Int = 120
private const val MOVED_WINDOW_X: Int = 520
private const val MOVED_WINDOW_Y: Int = 180
private const val WINDOW_SETTLE_MS: Long = 500
private const val WINDOW_READY_TIMEOUT_SECONDS: Long = 5
private const val WAIT_TIMEOUT_SECONDS: Long = 5
private const val WAIT_POLL_MS: Long = 50
private const val ROBOT_AUTO_DELAY_MS: Int = 40
private const val COLOR_TOLERANCE: Int = 8

private val STARTING_COLOR = Color(0xF7, 0xF7, 0xF7)
private val CAPTURED_COLOR = Color(0x20, 0xC9, 0x97)

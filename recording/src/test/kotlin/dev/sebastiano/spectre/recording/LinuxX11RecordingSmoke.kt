@file:JvmName("LinuxX11RecordingSmoke")

package dev.sebastiano.spectre.recording

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual end-to-end smoke for the Linux helper's Xorg/Xvfb region recording path. Run via
 * `./gradlew :recording:runLinuxX11RecordingSmoke` from an X display; the task opens a small
 * JFrame, records it for ~3 seconds through [AutoRecorder], and prints the resulting file path +
 * size.
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
    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-linux-x11-smoke.mp4")
    val midRecordingPng =
        Path.of(System.getProperty("java.io.tmpdir"), "spectre-linux-x11-smoke.png")
    Files.deleteIfExists(output)
    Files.deleteIfExists(midRecordingPng)

    val (window, label) = openSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)
    waitForVisibleFrame(window)

    val frameBoundsAtStart = window.bounds
    val handle =
        AutoRecorder()
            .startRegion(
                region = frameBoundsAtStart,
                output = output,
                options = RecordingOptions(frameRate = 30, captureCursor = true),
            )

    println("Recording started -> $output (pid=${ProcessHandle.current().pid()})")

    var ticks = 0
    val animator =
        Thread.ofPlatform().daemon().name("spectre-linux-x11-smoke-animator").start {
            val deadline = System.nanoTime() + RECORD_DURATION_MS * 1_000_000L
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted) {
                ticks += 1
                val current = ticks
                SwingUtilities.invokeLater { label.text = "tick=$current" }
                Thread.sleep(ANIMATION_INTERVAL_MS.toLong())
            }
        }

    Thread.sleep(RECORD_DURATION_MS / 2)
    val frameBounds = window.bounds
    val robotShot = Robot().createScreenCapture(frameBounds)
    ImageIO.write(robotShot, "png", File(midRecordingPng.toString()))
    checkVisibleReferenceFrame(robotShot, frameBounds)
    println("Robot screenshot of smoke window bounds $frameBounds -> $midRecordingPng")

    animator.join()
    println("Animation done; ticks fired: $ticks")

    handle.stop()
    val sizeBytes = if (Files.exists(output)) Files.size(output) else -1
    println("Recording stopped -> $output ($sizeBytes bytes)")

    SwingUtilities.invokeLater {
        window.isVisible = false
        window.dispose()
    }
}

private fun waitForVisibleFrame(window: Window) {
    val robot = Robot()
    val deadline = System.nanoTime() + VISIBLE_FRAME_TIMEOUT_MS * NANOS_PER_MILLI
    var lastShot: BufferedImage? = null
    while (System.nanoTime() < deadline) {
        val bounds = window.bounds
        val shot = robot.createScreenCapture(bounds)
        if (hasEnoughVisiblePixels(shot)) return
        lastShot = shot
        SwingUtilities.invokeAndWait {
            window.toFront()
            window.repaint()
            Toolkit.getDefaultToolkit().sync()
        }
        robot.waitForIdle()
        Thread.sleep(VISIBLE_FRAME_POLL_MS)
    }
    checkVisibleReferenceFrame(checkNotNull(lastShot), window.bounds)
}

private fun checkVisibleReferenceFrame(image: BufferedImage, bounds: java.awt.Rectangle) {
    val visiblePixels = visiblePixelCount(image)
    val totalPixels = totalPixelCount(image)
    val minimumVisiblePixels = totalPixels / MIN_VISIBLE_PIXEL_DIVISOR
    check(visiblePixels >= minimumVisiblePixels) {
        "X11 region smoke reference screenshot for $bounds is effectively black " +
            "($visiblePixels/$totalPixels visible pixels). If this is running inside a native " +
            "Wayland session with XWayland, the X11 root framebuffer may not contain the " +
            "composited desktop; run this smoke under a real Xorg/Xvfb display, or validate the " +
            "normal Wayland portal route instead."
    }
}

private fun hasEnoughVisiblePixels(image: BufferedImage): Boolean =
    visiblePixelCount(image) >= totalPixelCount(image) / MIN_VISIBLE_PIXEL_DIVISOR

private fun totalPixelCount(image: BufferedImage): Int = image.width * image.height

private fun visiblePixelCount(image: BufferedImage): Int {
    var visiblePixels = 0
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val red = rgb shr 16 and COLOR_MASK
            val green = rgb shr 8 and COLOR_MASK
            val blue = rgb and COLOR_MASK
            if (red + green + blue > MIN_VISIBLE_RGB_SUM) visiblePixels += 1
        }
    }
    return visiblePixels
}

private fun openSmokeWindow(): Pair<Window, JLabel> {
    val ready = CountDownLatch(1)
    var windowRef: Window? = null
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
        val window =
            JWindow().apply {
                contentPane = panel
                size = Dimension(WINDOW_WIDTH, WINDOW_HEIGHT)
                setBounds(WINDOW_X, WINDOW_Y, WINDOW_WIDTH, WINDOW_HEIGHT)
                isVisible = true
                toFront()
                repaint()
                Toolkit.getDefaultToolkit().sync()
            }
        windowRef = window
        labelRef = label
        ready.countDown()
    }
    check(ready.await(WINDOW_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "Smoke window never came up within ${WINDOW_OPEN_TIMEOUT_SECONDS}s"
    }
    return checkNotNull(windowRef) to checkNotNull(labelRef)
}

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 240
private const val WINDOW_X = 400
private const val WINDOW_Y = 280
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_OPEN_TIMEOUT_SECONDS = 5L
private const val RECORD_DURATION_MS = 3_000L
private const val ANIMATION_INTERVAL_MS = 50
private const val LABEL_FONT_SIZE = 48
private const val VISIBLE_FRAME_TIMEOUT_MS = 5_000L
private const val VISIBLE_FRAME_POLL_MS = 100L
private const val NANOS_PER_MILLI = 1_000_000L
private const val COLOR_MASK = 0xff
private const val MIN_VISIBLE_RGB_SUM = 60
private const val MIN_VISIBLE_PIXEL_DIVISOR = 20

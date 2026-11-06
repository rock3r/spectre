@file:JvmName("WaylandMutterWindowSmoke")

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Attended smoke for GNOME Wayland window capture through Mutter window streams (#642). Run after
 * `spectre permissions request` with `./gradlew :recording:runWaylandMutterWindowSmoke`. It must
 * open **no** dialog.
 *
 * It opens a red window, covers it with a blue one that takes focus, then captures and records the
 * red window, and captures a third, new window. A real window capture shows no blue: the frames
 * come from the red window's own Mutter stream, not from the monitor.
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
    val outDir = Path.of(System.getProperty("java.io.tmpdir"), "spectre-mutter-window-smoke")
    Files.createDirectories(outDir)
    val red = openWindow("Spectre Mutter smoke red", Color.RED, x = 200, y = 200)
    Thread.sleep(SETTLE_MS)
    val blue = openWindow("Spectre Mutter smoke blue", Color.BLUE, x = 300, y = 260)
    Thread.sleep(SETTLE_MS)

    val still = AutoScreenshotter().captureWindow(red.asTitledWindow())
    val stillFile = outDir.resolve("red-covered.png")
    ImageIO.write(still, "png", stillFile.toFile())
    println("Still of the covered red window: $stillFile (${still.width}x${still.height})")
    check(blueShare(still) < MAX_BLUE_SHARE) {
        "the covered red window's still shows the blue window: not a real window capture"
    }

    val video = outDir.resolve("red-covered.mp4")
    Files.deleteIfExists(video)
    val handle = AutoRecorder().startWindow(red.asTitledWindow(), video, RecordingOptions())
    Thread.sleep(RECORD_MS)
    handle.stop()
    val videoBytes = Files.size(video)
    println("Recording of the covered red window: $video ($videoBytes bytes)")
    check(videoBytes > MIN_VIDEO_BYTES) { "window recording is suspiciously small" }

    val green = openWindow("Spectre Mutter smoke new window", Color.GREEN, x = 500, y = 120)
    Thread.sleep(SETTLE_MS)
    val newStill = AutoScreenshotter().captureWindow(green.asTitledWindow())
    ImageIO.write(newStill, "png", outDir.resolve("new-window.png").toFile())
    println("Still of a new window: ${newStill.width}x${newStill.height}")

    listOf(red, blue, green).forEach { SwingUtilities.invokeAndWait { it.dispose() } }
    println("PASS — check that no Share or window-picker dialog appeared during this smoke.")
}

private fun openWindow(title: String, color: Color, x: Int, y: Int): JFrame {
    val frame = AtomicReference<JFrame>()
    SwingUtilities.invokeAndWait {
        frame.set(
            JFrame(title).apply {
                contentPane = JPanel().apply { background = color }
                setBounds(x, y, WINDOW_WIDTH, WINDOW_HEIGHT)
                isVisible = true
                toFront()
            }
        )
    }
    return frame.get()
}

private fun blueShare(image: BufferedImage): Double {
    var blue = 0
    for (y in 0 until image.height) {
        for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val b = rgb and 0xFF
            if (b > 200 && r < 60 && g < 60) blue++
        }
    }
    return blue.toDouble() / (image.width * image.height)
}

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 320
private const val SETTLE_MS = 1_500L
private const val RECORD_MS = 3_000L
private const val MIN_VIDEO_BYTES = 10_000L
private const val MAX_BLUE_SHARE = 0.01

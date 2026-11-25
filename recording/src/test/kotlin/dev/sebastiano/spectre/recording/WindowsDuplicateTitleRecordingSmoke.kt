@file:JvmName("WindowsDuplicateTitleRecordingSmoke")

package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Color
import java.awt.Rectangle
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.assertFailsWith

/**
 * Run on a Windows desktop with ffmpeg on PATH via
 * :recording:runWindowsDuplicateTitleRecordingSmoke.
 */
fun main() {
    val frames = mutableListOf<JFrame>()
    val directory = Files.createTempDirectory("spectre-wgc-duplicate-")
    val recorder = AutoRecorder()
    val crops = listOf(null, Rectangle(12, 14, 320, 240))
    try {
        SwingUtilities.invokeAndWait {
            for ((index, color) in listOf(Color.RED, Color.BLUE).withIndex()) {
                frames +=
                    JFrame("Spectre WGC twins").apply {
                        isUndecorated = true
                        contentPane.background = color
                        setBounds(20 + index * 420, 50, 400, 320)
                        isVisible = true
                    }
            }
        }
        val rejected = directory.resolve("rejected.mp4")
        fun expectAmbiguous(start: () -> RecordingHandle) {
            val error = assertFailsWith<IllegalStateException> { start().stop() }
            check("(exit 7)" in error.message.orEmpty()) { error.toString() }
            check(!Files.exists(rejected))
        }
        expectAmbiguous {
            recorder.startWindowByTitle(
                "Spectre WGC twins",
                ProcessHandle.current().pid(),
                rejected,
            )
        }
        for ((index, frame) in frames.withIndex()) {
            val scale = frame.graphicsConfiguration.defaultTransform
            for (crop in crops) {
                val output = directory.resolve("window-$index-crop-${crop != null}.mp4")
                val handle =
                    recorder.startWindow(
                        frame.asTitledWindow(),
                        output,
                        cropInWindow = crop,
                        scaleX = scale.scaleX,
                        scaleY = scale.scaleY,
                    )
                try {
                    Thread.sleep(1200)
                } finally {
                    handle.stop()
                }
                val png = directory.resolve(output.fileName.toString() + ".png")
                val decoder =
                    ProcessBuilder(
                            "ffmpeg",
                            "-v",
                            "error",
                            "-ss",
                            "0.5",
                            "-i",
                            output.toString(),
                            "-frames:v",
                            "1",
                            png.toString(),
                        )
                        .inheritIO()
                        .start()
                check(decoder.waitFor() == 0)
                val image = ImageIO.read(png.toFile())
                val center = Color(image.getRGB(image.width / 2, image.height / 2))
                check(
                    if (index == 0) center.red > center.blue + 100
                    else center.blue > center.red + 100
                ) {
                    "Unexpected target color $center in $output"
                }
                if (crop != null) {
                    fun even(n: Double) = (n.roundToInt() + 1) / 2 * 2
                    check(
                        image.width == even(crop.width * scale.scaleX) &&
                            image.height == even(crop.height * scale.scaleY)
                    )
                }
            }
        }
        SwingUtilities.invokeAndWait { frames[1].bounds = frames[0].bounds }
        for (crop in crops) {
            expectAmbiguous {
                recorder.startWindow(frames[0].asTitledWindow(), rejected, cropInWindow = crop)
            }
        }
        println("Verified duplicate-title recordings in $directory")
    } finally {
        SwingUtilities.invokeAndWait { frames.forEach(JFrame::dispose) }
    }
}

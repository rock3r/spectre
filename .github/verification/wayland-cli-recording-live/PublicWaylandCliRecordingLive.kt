package verification

import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Color
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.io.path.deleteIfExists

/** Temporary hosted-seat proof. It intentionally imports only Spectre's public recording API. */
object PublicWaylandCliRecordingLive {
    @JvmStatic
    fun main(args: Array<String>) {
        val artifacts = Path.of(requireNotNull(System.getenv("SMOKE_ARTIFACTS")))
        val decoyPid = requireNotNull(System.getenv("DECOY_PID")).toLong()
        val red = window(TITLE, Color.RED, 180, 170)
        try {
            Thread.sleep(1_500)
            requireActivePid(decoyPid, "before-red")

            val mismatch = artifacts.resolve("mismatched-fixture-must-not-exist.png")
            mismatch.deleteIfExists()
            val rejected = runCatching {
                AutoScreenshotter().captureWindow(
                    object : TitledWindow {
                        override var title: String? = TITLE
                        override val bounds = Rectangle(180, 170, WIDTH, HEIGHT)
                    },
                    decoyPid,
                )
            }.exceptionOrNull()
            check(rejected != null && !Files.exists(mismatch)) {
                "a non-caller-owned PID fixture was not rejected without media"
            }
            Files.writeString(artifacts.resolve("mismatched-pid-rejection.txt"), rejected.toString())

            val redImage = AutoScreenshotter().captureWindow(red.asTitledWindow())
            val redFile = artifacts.resolve("red-covered.png")
            ImageIO.write(redImage, "png", redFile.toFile())
            requireColor(redImage, Color.RED, "red still")
            requireActivePid(decoyPid, "after-red")

            val movie = artifacts.resolve("red-covered.mp4")
            movie.deleteIfExists()
            val recording = AutoRecorder().startWindow(red.asTitledWindow(), movie, RecordingOptions())
            Thread.sleep(3_500)
            recording.stop()
            check(Files.size(movie) > 10_000) { "recording is suspiciously small" }
            requireActivePid(decoyPid, "after-recording")

            val green = window("Spectre governed green target", Color.GREEN, 500, 100)
            try {
                Thread.sleep(1_000)
                Robot().apply {
                    mouseMove(430, 340)
                    mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                    mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                }
                Thread.sleep(750)
                requireActivePid(decoyPid, "green-refocused")
                val greenImage = AutoScreenshotter().captureWindow(green.asTitledWindow())
                ImageIO.write(greenImage, "png", artifacts.resolve("green-covered.png").toFile())
                requireColor(greenImage, Color.GREEN, "green still")
                requireActivePid(decoyPid, "after-green")
            } finally {
                SwingUtilities.invokeAndWait { green.dispose() }
            }
            Files.writeString(
                artifacts.resolve("java-live-evidence.json"),
                """{"decoyPid":$decoyPid,"targetPid":${ProcessHandle.current().pid()},"publicApi":true,"mismatchedPidRejected":true}\n""",
            )
        } finally {
            SwingUtilities.invokeAndWait { red.dispose() }
        }
    }

    private fun window(title: String, color: Color, x: Int, y: Int): JFrame {
        lateinit var result: JFrame
        SwingUtilities.invokeAndWait {
            result = JFrame(title).apply {
                contentPane = JPanel().apply { background = color }
                setBounds(x, y, WIDTH, HEIGHT)
                isVisible = true
            }
        }
        return result
    }

    private fun requireActivePid(pid: Long, phase: String) {
        val active = ProcessBuilder("xdotool", "getactivewindow", "getwindowpid").start()
        val observed = active.inputReader().readText().trim().toLongOrNull()
        check(active.waitFor() == 0 && observed == pid) {
            "blue decoy lost real-seat focus at $phase: expected $pid, observed $observed"
        }
    }

    private fun requireColor(image: BufferedImage, expected: Color, label: String) {
        var correct = 0
        var blue = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = Color(image.getRGB(x, y))
            if (color.blue > 200 && color.red < 60 && color.green < 60) blue++
            if (kotlin.math.abs(color.red - expected.red) < 25 &&
                kotlin.math.abs(color.green - expected.green) < 25 &&
                kotlin.math.abs(color.blue - expected.blue) < 25) correct++
        }
        val pixels = image.width.toLong() * image.height
        check(correct.toDouble() / pixels > 0.50 && blue.toDouble() / pixels < 0.01) {
            "$label color coverage failed: correct=$correct blue=$blue pixels=$pixels"
        }
    }

    private const val TITLE = "Spectre governed same-title target"
    private const val WIDTH = 480
    private const val HEIGHT = 320
}

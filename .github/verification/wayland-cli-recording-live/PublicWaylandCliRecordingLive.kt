package verification

import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Color
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
        val decoyXid = requireNotNull(System.getenv("DECOY_XID")).toLong()
        val red = window(TITLE, Color.RED, 180, 170)
        try {
            Thread.sleep(1_500)
            focusDecoy(decoyPid, decoyXid, "before-title-rejection")
            val rejected = runCatching {
                AutoScreenshotter().captureWindowByTitle(TITLE, decoyPid)
            }.exceptionOrNull()
            check(rejected is UnsupportedOperationException &&
                rejected.message.orEmpty().contains("unsupported on this platform")) {
                "documented Linux cross-process-by-title rejection was not observed: $rejected"
            }
            java.nio.file.Files.writeString(
                artifacts.resolve("cross-process-title-rejection.txt"),
                "${rejected::class.qualifiedName}: ${rejected.message}\n",
            )

            focusDecoy(decoyPid, decoyXid, "before-red-still")
            val redImage = AutoScreenshotter().captureWindow(red.asTitledWindow())
            val redFile = artifacts.resolve("red-covered.png")
            ImageIO.write(redImage, "png", redFile.toFile())
            requireColor(redImage, Color.RED, "red still")
            requireActive(decoyPid, decoyXid, "after-red")

            focusDecoy(decoyPid, decoyXid, "before-red-recording")
            val movie = artifacts.resolve("red-covered.mp4")
            movie.deleteIfExists()
            val recording = AutoRecorder().startWindow(red.asTitledWindow(), movie, RecordingOptions())
            Thread.sleep(3_500)
            recording.stop()
            check(Files.size(movie) > 10_000) { "recording is suspiciously small" }
            requireActive(decoyPid, decoyXid, "after-recording")

            val green = window("Spectre governed green target", Color.GREEN, 500, 100)
            try {
                Thread.sleep(1_000)
                focusDecoy(decoyPid, decoyXid, "before-green-still")
                requireActive(decoyPid, decoyXid, "green-refocused")
                val greenImage = AutoScreenshotter().captureWindow(green.asTitledWindow())
                ImageIO.write(greenImage, "png", artifacts.resolve("green-covered.png").toFile())
                requireColor(greenImage, Color.GREEN, "green still")
                requireActive(decoyPid, decoyXid, "after-green")
            } finally {
                SwingUtilities.invokeAndWait { green.dispose() }
            }
            Files.writeString(
                artifacts.resolve("java-live-evidence.json"),
                """{"decoyPid":$decoyPid,"targetPid":${ProcessHandle.current().pid()},"publicApi":true,"crossProcessByTitleUnsupported":true,"awtOwnerArgumentIdentityChecked":false}
                """.trimIndent() + "\n",
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

    private fun requireActive(pid: Long, xid: Long, phase: String) {
        val active = ProcessBuilder("xdotool", "getactivewindow").start()
        val observedXid = active.inputReader().readText().trim().toLongOrNull()
        check(active.waitFor() == 0 && observedXid == xid) {
            "blue decoy lost real-seat focus at $phase: expected XID $xid, observed $observedXid"
        }
        val owner = ProcessBuilder("xdotool", "getwindowpid", xid.toString()).start()
        val observedPid = owner.inputReader().readText().trim().toLongOrNull()
        check(owner.waitFor() == 0 && observedPid == pid) {
            "blue decoy XID owner changed at $phase: expected PID $pid, observed $observedPid"
        }
    }

    private fun focusDecoy(pid: Long, xid: Long, phase: String) {
        requireActive(pid, xid, "$phase/pre-activation")
        check(ProcessBuilder("xdotool", "windowactivate", "--sync", xid.toString()).start().waitFor() == 0)
        check(ProcessBuilder("xdotool", "windowraise", xid.toString()).start().waitFor() == 0)
        Thread.sleep(300)
        requireActive(pid, xid, phase)
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

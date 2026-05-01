@file:JvmName("WaylandPortalSmoke")

package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Manual smoke for the **stage-2 Wayland portal handshake**. Run via `./gradlew
 * :recording:runWaylandPortalSmoke` on a Wayland session — opens a JFrame so the compositor's
 * "share your screen" dialog has something to point at, calls [WaylandPortalRecorder.start],
 * and **expects** a stage-2-limitation [UnsupportedOperationException]. A "PASS" result means
 * the portal handshake completed cleanly: `CreateSession` → `SelectSources` → `Start` round-
 * tripped, the response parsed into a non-zero PipeWire node id and stream size, and the
 * recorder threw with the documented stage-3 follow-up message instead of producing a 0-byte
 * mp4.
 *
 * Counterpart to [dev.sebastiano.spectre.recording.FfmpegX11GrabSmoke] (Linux Xorg) and
 * [dev.sebastiano.spectre.recording.FfmpegGdigrabSmoke] (Windows). Once stage 3's FD-inheritance
 * piece lands, the recorder will start producing real frames and this smoke flips from
 * "expected throw" to "expected non-zero mp4 with non-trivial pixel content."
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
    val output = Path.of(System.getProperty("java.io.tmpdir"), "spectre-wayland-portal-smoke.mp4")
    Files.deleteIfExists(output)

    val (frame, _) = openSmokeWindow()
    Thread.sleep(WINDOW_SETTLE_MS)

    val recorder = WaylandPortalRecorder()
    println(
        "(NOTE: a compositor permission dialog will pop on first run today; pick the monitor " +
            "with the JFrame and click \"Share.\" Subsequent runs reuse the grant silently.)"
    )

    val expected =
        runCatching {
            recorder.start(
                region = frame.bounds,
                output = output,
                options = RecordingOptions(frameRate = 30, captureCursor = true),
            )
        }
    val throwable = expected.exceptionOrNull()

    SwingUtilities.invokeLater {
        frame.isVisible = false
        frame.dispose()
    }

    when {
        expected.isSuccess -> {
            println(
                "FAIL — start() returned a handle, but stage 2 is supposed to throw before " +
                    "spawning the encoder. Either stage 3 has landed (in which case update " +
                    "this smoke to assert real frames) or the stage-2 guard regressed."
            )
            error("stage-2 smoke unexpectedly succeeded")
        }
        throwable is UnsupportedOperationException &&
            (throwable.message?.contains("stage 2") == true ||
                throwable.message?.contains("portal handshake completed") == true) -> {
            println("PASS — portal handshake completed; recorder threw the documented stage-2 " +
                "limitation as expected. Stage 3 follow-up will replace the throw with the " +
                "FD-passing encoder spawn.")
            println("Stage-2 message: ${throwable.message}")
        }
        else -> {
            println(
                "FAIL — recorder.start() threw an unexpected exception. The stage-2 throw is " +
                    "supposed to be an UnsupportedOperationException naming the portal " +
                    "handshake state and the stage-3 follow-up. Got: $throwable"
            )
            throwable?.printStackTrace()
            error("stage-2 smoke threw the wrong exception type")
        }
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
            JFrame("Spectre Wayland portal smoke").apply {
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
    check(ready.await(WINDOW_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "JFrame never came up within ${WINDOW_OPEN_TIMEOUT_SECONDS}s"
    }
    return frameRef!! to labelRef!!
}

private const val WINDOW_WIDTH = 480
private const val WINDOW_HEIGHT = 240
private const val WINDOW_SETTLE_MS = 500L
private const val WINDOW_OPEN_TIMEOUT_SECONDS = 5L
private const val LABEL_FONT_SIZE = 48

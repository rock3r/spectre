package verification

import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.io.path.deleteIfExists

/** Temporary hosted-seat proof. It intentionally imports only Spectre's public recording API. */
object PublicWaylandCliRecordingLive {
    private lateinit var receipts: Path

    @JvmStatic
    fun main(args: Array<String>) {
        val artifacts = Path.of(requireNotNull(System.getenv("SMOKE_ARTIFACTS")))
        receipts = artifacts.resolve("java-live-phase-receipts.jsonl")
        val decoyPid = requireNotNull(System.getenv("DECOY_PID")).toLong()
        val targetPid = ProcessHandle.current().pid()
        var red: JFrame? = window(TITLE, Color.RED)
        Files.writeString(Path.of(requireNotNull(System.getenv("DECOY_REVEAL"))), "target-ready\n")
        val decoyXid = awaitDecoyXid(Path.of(requireNotNull(System.getenv("DECOY_XID_FILE"))))
        try {
            Thread.sleep(1_500)
            focusDecoy(decoyPid, decoyXid, "before-title-rejection", red!!.bounds)
            val rejected =
                FocusMonitor(decoyPid, decoyXid, "title-rejection-during", red!!.bounds).use {
                    monitor ->
                    val result =
                        runCatching {
                            AutoScreenshotter().captureWindowByTitle(TITLE, decoyPid)
                        }
                            .exceptionOrNull()
                    monitor.requireObserved()
                    result
                }
            check(
                rejected is UnsupportedOperationException &&
                    rejected.message.orEmpty().contains("unsupported on this platform")
            ) {
                "documented Linux cross-process-by-title rejection was not observed: $rejected"
            }
            Files.writeString(
                artifacts.resolve("cross-process-title-rejection.txt"),
                "${rejected::class.qualifiedName}: ${rejected.message}\n",
            )
            phase("title-rejection", decoyPid, decoyXid, red!!.bounds, "negative-result")

            focusDecoy(decoyPid, decoyXid, "red-still-before", red!!.bounds)
            val redImage = captureStill(red!!, decoyPid, decoyXid, "red-still")
            val redFile = artifacts.resolve("red-covered.png")
            ImageIO.write(redImage, "png", redFile.toFile())
            requireColor(redImage, Color.RED, "red still")
            phase(
                "red-still-after",
                decoyPid,
                decoyXid,
                red!!.bounds,
                "${redFile.fileName}:${redImage.width}x${redImage.height}",
            )

            focusDecoy(decoyPid, decoyXid, "red-recording-before", red!!.bounds)
            val movie = artifacts.resolve("red-covered.mp4")
            movie.deleteIfExists()
            val beforeStart = System.nanoTime()
            val recording =
                AutoRecorder().startWindow(red!!.asTitledWindow(), movie, RecordingOptions())
            val startReturned = System.nanoTime()
            phase(
                "red-recording-start-returned",
                decoyPid,
                decoyXid,
                red!!.bounds,
                "${movie.fileName}:beforeStartNs=$beforeStart:startReturnedNs=$startReturned",
            )
            repeat(7) {
                Thread.sleep(500)
                phase(
                    "red-recording-during",
                    decoyPid,
                    decoyXid,
                    red!!.bounds,
                    movie.fileName.toString(),
                )
            }
            recording.stop()
            val stopped = System.nanoTime()
            val recordingWallSeconds = (stopped - beforeStart) / 1_000_000_000.0
            val elapsedAfterStartReturnsSeconds = (stopped - startReturned) / 1_000_000_000.0
            phase(
                "red-recording-after",
                decoyPid,
                decoyXid,
                red!!.bounds,
                "${movie.fileName}:recordingWallSeconds=$recordingWallSeconds:" +
                    "elapsedAfterStartReturnsSeconds=$elapsedAfterStartReturnsSeconds",
            )

            SwingUtilities.invokeAndWait { red!!.dispose() }
            red = null
            val green = window(TITLE, Color.GREEN)
            try {
                val greenReady = Path.of(requireNotNull(System.getenv("GREEN_TARGET_READY")))
                Files.writeString(greenReady, "green-ready\n")
                awaitSignal(Path.of(requireNotNull(System.getenv("GREEN_TARGET_VERIFIED"))), 15)
                Thread.sleep(1_000)
                focusDecoy(decoyPid, decoyXid, "green-still-before", green.bounds)
                val greenImage = captureStill(green, decoyPid, decoyXid, "green-still")
                val greenFile = artifacts.resolve("green-covered.png")
                ImageIO.write(greenImage, "png", greenFile.toFile())
                requireColor(greenImage, Color.GREEN, "green still")
                phase(
                    "green-still-after",
                    decoyPid,
                    decoyXid,
                    green.bounds,
                    "${greenFile.fileName}:${greenImage.width}x${greenImage.height}",
                )
            } finally {
                SwingUtilities.invokeAndWait { green.dispose() }
            }
            Files.writeString(
                artifacts.resolve("java-live-evidence.json"),
                """{"decoyPid":$decoyPid,"targetPid":$targetPid,"publicApi":true,"crossProcessByTitleUnsupported":true,"awtOwnerArgumentIdentityChecked":false,"recordingWallSeconds":$recordingWallSeconds,"elapsedAfterStartReturnsSeconds":$elapsedAfterStartReturnsSeconds}
                """
                    .trimIndent() + "\n",
            )
        } finally {
            red?.let { frame -> SwingUtilities.invokeAndWait { frame.dispose() } }
        }
    }

    private fun captureStill(frame: JFrame, pid: Long, xid: Long, label: String): BufferedImage {
        FocusMonitor(pid, xid, "$label-during", frame.bounds).use { monitor ->
            val image = AutoScreenshotter().captureWindow(frame.asTitledWindow())
            monitor.requireObserved()
            return image
        }
    }

    private class FocusMonitor(
        private val pid: Long,
        private val xid: Long,
        private val phaseName: String,
        private val geometry: Rectangle,
    ) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val samples = mutableListOf<Pair<Long?, Long?>>()
        private val thread = Thread {
            while (running.get()) {
                val actualXid = activeXid()
                val actualPid = if (actualXid == null) null else ownerPid(actualXid)
                synchronized(samples) { samples += actualXid to actualPid }
                Thread.sleep(10)
            }
        }
            .apply { start() }

        fun requireObserved() {
            close()
            val copy = synchronized(samples) { samples.toList() }
            val focusMatches = copy.isNotEmpty() && copy.all { it.first == xid && it.second == pid }
            if (!focusMatches) {
                phase("$phaseName-failure", pid, xid, geometry, "focusSamples=$copy")
            }
            check(focusMatches) {
                "decoy focus changed during $phaseName: expected XID/PID $xid/$pid, observed $copy"
            }
            phase(phaseName, pid, xid, geometry, "focusSamples=${copy.size}")
        }

        override fun close() {
            if (running.getAndSet(false)) thread.join()
        }
    }

    private fun window(title: String, color: Color): JFrame {
        lateinit var result: JFrame
        SwingUtilities.invokeAndWait {
            result =
                JFrame(title).apply {
                    contentPane = JPanel().apply { background = color }
                    isAutoRequestFocus = false
                    setFocusableWindowState(false)
                    setBounds(TARGET_X, TARGET_Y, WIDTH, HEIGHT)
                    isVisible = true
                }
        }
        return result
    }

    private fun activeXid() = command("xdotool", "getactivewindow").toLongOrNull()

    private fun ownerPid(xid: Long) =
        command("xdotool", "getwindowpid", xid.toString()).toLongOrNull()

    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).start()
        check(process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "command timed out: ${args.joinToString(" ")}"
        }
        val output = process.inputReader().readText().trim()
        check(process.exitValue() == 0) { "command failed: ${args.joinToString(" ")}" }
        return output
    }

    private fun requireOwner(pid: Long, xid: Long, phase: String) {
        val observedPid = ownerPid(xid)
        check(observedPid == pid) {
            "blue decoy XID owner changed at $phase: expected PID $pid, observed $observedPid"
        }
    }

    private fun requireActive(pid: Long, xid: Long, phase: String, geometry: Rectangle) {
        val observedXid = activeXid()
        val observedPid = if (observedXid == null) null else ownerPid(observedXid)
        check(observedXid == xid && observedPid == pid) {
            "blue decoy focus changed at $phase: expected XID/PID $xid/$pid, " +
                "observed $observedXid/$observedPid"
        }
        phase(phase, pid, xid, geometry, "focus")
    }

    private fun focusDecoy(pid: Long, xid: Long, phase: String, geometry: Rectangle) {
        try {
            requireOwner(pid, xid, "$phase/pre-activation")
            if (activeXid() == xid) {
                requireActive(pid, xid, phase, geometry)
                return
            }
            command("xdotool", "windowraise", xid.toString())
            command("xdotool", "mousemove", "--window", xid.toString(), "240", "160")
            command("xdotool", "click", "1")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (activeXid() != xid && System.nanoTime() < deadline) Thread.sleep(25)
            requireActive(pid, xid, phase, geometry)
        } catch (error: Throwable) {
            phase("$phase-failure", pid, xid, geometry, "status=failure:${error.message}")
            throw error
        }
    }

    private fun phase(
        name: String,
        expectedPid: Long,
        expectedXid: Long,
        geometry: Rectangle,
        media: String,
    ) {
        val actualXid = activeXid()
        val actualPid = if (actualXid == null) null else ownerPid(actualXid)
        val focusMatches = actualXid == expectedXid && actualPid == expectedPid
        val failed = name.endsWith("-failure") || !focusMatches
        val line =
            """{"phase":"$name","status":"${if (failed) "failure" else "ok"}","monotonicNs":${System.nanoTime()},"expectedXid":$expectedXid,"actualXid":$actualXid,"expectedPid":$expectedPid,"actualPid":$actualPid,"geometry":{"x":${geometry.x},"y":${geometry.y},"width":${geometry.width},"height":${geometry.height}},"size":{"width":${geometry.width},"height":${geometry.height}},"media":"${media.replace("\\", "\\\\").replace("\"", "\\\"")}"}
        """
                .trimIndent() + "\n"
        Files.writeString(
            receipts,
            line,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.SYNC,
        )
        if (!name.endsWith("-failure")) {
            check(focusMatches) {
                "blue decoy focus changed at $name: expected XID/PID $expectedXid/$expectedPid, " +
                    "observed $actualXid/$actualPid"
            }
        }
    }

    private fun awaitDecoyXid(path: Path): Long {
        awaitSignal(path, 15)
        return Files.readString(path).trim().toLong()
    }

    private fun awaitSignal(path: Path, timeoutSeconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!Files.isRegularFile(path) && System.nanoTime() < deadline) Thread.sleep(25)
        check(Files.isRegularFile(path)) { "$path was not supplied within $timeoutSeconds seconds" }
    }

    private fun requireColor(image: BufferedImage, expected: Color, label: String) {
        var correct = 0
        var blue = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = Color(image.getRGB(x, y))
            if (color.blue > 200 && color.red < 60 && color.green < 60) blue++
            if (
                kotlin.math.abs(color.red - expected.red) < 25 &&
                    kotlin.math.abs(color.green - expected.green) < 25 &&
                    kotlin.math.abs(color.blue - expected.blue) < 25
            )
                correct++
        }
        val pixels = image.width.toLong() * image.height
        check(correct.toDouble() / pixels > 0.50 && blue.toDouble() / pixels < 0.01) {
            "$label color coverage failed: correct=$correct blue=$blue pixels=$pixels"
        }
    }

    private const val TITLE = "Spectre governed same-title target"
    private const val TARGET_X = 300
    private const val TARGET_Y = 240
    private const val WIDTH = 480
    private const val HEIGHT = 320
}

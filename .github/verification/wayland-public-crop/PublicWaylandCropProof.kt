package verification

import dev.sebastiano.spectre.recording.AutoRecorder
import dev.sebastiano.spectre.recording.AutoScreenshotter
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asTitledWindow
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.io.path.deleteIfExists

/** Ephemeral hosted proof which deliberately uses only Spectre's public capture API. */
object PublicWaylandCropProof {
    @JvmStatic
    fun main(args: Array<String>) {
        val artifacts = Path.of(requireNotNull(System.getenv("SMOKE_ARTIFACTS")))
        val decoyPid = requireNotNull(System.getenv("DECOY_PID")).toLong()
        val decoyXid = requireNotNull(System.getenv("DECOY_XID")).toLong()
        val receipts = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            listOf(
                Case("red-decorated", Color(220, 20, 30), true),
                Case("green-undecorated", Color(20, 190, 45), false),
            ).forEach {
                try {
                    runCase(it, artifacts, decoyPid, decoyXid, receipts)
                } catch (problem: Throwable) {
                    receipts += "{\"case\":\"${it.name}\",\"phase\":\"failed\",\"ok\":false}"
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                } finally {
                    Files.writeString(artifacts.resolve("crop-phase-receipts.json"), "[${receipts.joinToString(",")} ]\n")
                }
            }
        } finally {
            Files.writeString(artifacts.resolve("crop-phase-receipts.json"), "[${receipts.joinToString(",")} ]\n")
        }
        failure?.let { throw it }
    }

    private fun runCase(case: Case, artifacts: Path, decoyPid: Long, decoyXid: Long, receipts: MutableList<String>) {
        val panel = PatternPanel(case.color)
        val frame = onEdt {
            JFrame(TITLE).apply {
                isUndecorated = !case.decorated
                contentPane = panel
                setBounds(200, 200, 480, 320)
                isVisible = true
            }
        }
        try {
            Thread.sleep(800)
            val crop = onEdt {
                val top = frame.locationOnScreen
                val content = panel.locationOnScreen
                // This conversion is intentional: cropInWindow is relative to the AWT top-level,
                // not to its content panel. It therefore includes real decoration insets.
                Rectangle(content.x - top.x + 83, content.y - top.y + 61, CROP_W, CROP_H)
            }
            val transform = frame.graphicsConfiguration.defaultTransform
            val insets = frame.insets
            focusDecoy(decoyPid, decoyXid)
            val still = AutoScreenshotter().captureWindow(
                window = frame.asTitledWindow(),
                windowOwnerPid = ProcessHandle.current().pid(),
            )
            ImageIO.write(still, "png", artifacts.resolve("${case.name}-whole-public-still.png").toFile())
            val expectedWidth = (frame.width * transform.scaleX).toInt()
            val expectedHeight = (frame.height * transform.scaleY).toInt()
            check(still.width == expectedWidth && still.height == expectedHeight)
            val deviceCrop = Rectangle(
                (crop.x * transform.scaleX).toInt(),
                (crop.y * transform.scaleY).toInt(),
                (crop.width * transform.scaleX).toInt(),
                (crop.height * transform.scaleY).toInt(),
            )
            check(Rectangle(0, 0, still.width, still.height).contains(deviceCrop))
            val reference = still.getSubimage(deviceCrop.x, deviceCrop.y, deviceCrop.width, deviceCrop.height)
            ImageIO.write(reference, "png", artifacts.resolve("${case.name}-reference-crop-derived-from-public-still.png").toFile())
            requireFocused(decoyPid, decoyXid)
            receipts += "{\"case\":\"${case.name}\",\"phase\":\"still\",\"ok\":true}"

            val movie = artifacts.resolve("${case.name}-crop.mp4")
            movie.deleteIfExists()
            focusDecoy(decoyPid, decoyXid)
            val beforeFocus = requireFocused(decoyPid, decoyXid)
            val started = System.nanoTime()
            val handle = AutoRecorder().startWindow(
                window = frame.asTitledWindow(), output = movie, options = RecordingOptions(),
                cropInWindow = crop, scaleX = transform.scaleX, scaleY = transform.scaleY,
            )
            var tick = 0
            while (System.nanoTime() - started < 3_000_000_000L) {
                onEdt { panel.tick = tick; panel.repaint() }
                tick++
                Thread.sleep(120)
                requireFocused(decoyPid, decoyXid)
            }
            handle.stop()
            val ended = System.nanoTime()
            check(ended - started >= 3_000_000_000L)
            val afterFocus = requireFocused(decoyPid, decoyXid)
            check(beforeFocus == afterFocus)
            receipts += "{\"case\":\"${case.name}\",\"phase\":\"video\",\"ok\":true}"
            Files.writeString(
                artifacts.resolve("${case.name}-intended.json"),
                """{"case":"${case.name}","decorated":${case.decorated},"captureApi":{"still":"public AutoScreenshotter.captureWindow whole native window","reference":"getSubimage derived from public whole still; not a native screenshot-crop endpoint","movie":"public AutoRecorder.startWindow cropInWindow"},"requestedTopLevelBounds":{"x":200,"y":200,"width":480,"height":320},"capturedWhole":{"width":${still.width},"height":${still.height}},"contentInsets":{"left":${insets.left},"top":${insets.top},"right":${insets.right},"bottom":${insets.bottom}},"cropInWindow":{"x":${crop.x},"y":${crop.y},"width":${crop.width},"height":${crop.height},"units":"AWT user-space relative to top-level"},"deviceCrop":{"x":${deviceCrop.x},"y":${deviceCrop.y},"width":${deviceCrop.width},"height":${deviceCrop.height}},"graphicsTransform":{"scaleX":${transform.scaleX},"scaleY":${transform.scaleY}},"expectedPositiveMarker":"stable yellow 18x18 at crop (24,19)","dynamicIndicator":"moving white ticker inside crop","targetPid":${ProcessHandle.current().pid()},"decoyPid":$decoyPid,"decoyXid":$decoyXid,"actualOverlayIdentity":{"title":"$TITLE","ownerPid":${ProcessHandle.current().pid()}},"focus":{"beforeMovie":$beforeFocus,"afterMovie":$afterFocus},"timing":{"movieWallStartedMonotonicNs":$started,"movieWallEndedMonotonicNs":$ended,"requestedDurationNs":3000000000}}
""",
            )
        } finally {
            onEdt { frame.dispose() }
        }
    }

    private fun focusDecoy(pid: Long, xid: Long) {
        repeat(20) {
            check(ProcessBuilder("xdotool", "windowactivate", "--sync", xid.toString()).start().waitFor() == 0)
            check(ProcessBuilder("xdotool", "windowraise", xid.toString()).start().waitFor() == 0)
            Thread.sleep(100)
            if (runCatching { requireFocused(pid, xid) }.isSuccess) return
        }
        error("could not acquire verified decoy focus")
    }

    private fun requireFocused(pid: Long, xid: Long): Long {
        fun output(vararg command: String): Long {
            val process = ProcessBuilder(*command).start()
            val value = process.inputReader().readText().trim().toLong()
            check(process.waitFor() == 0)
            return value
        }
        check(output("xdotool", "getactivewindow") == xid)
        check(output("xdotool", "getwindowpid", xid.toString()) == pid)
        return xid
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private data class Case(val name: String, val color: Color, val decorated: Boolean)

    private class PatternPanel(private val base: Color) : JPanel() {
        var tick: Int = 0
        init { preferredSize = Dimension(480, 320) }
        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)
            graphics.color = base
            graphics.fillRect(0, 0, width, height)
            // The requested crop begins at content (83,61). A 37px translation selects a
            // polarity-opposite marker, so an origin error cannot pass on background alone.
            graphics.color = Color.CYAN
            graphics.fillRect(70, 80, 18, 18)
            graphics.color = Color.YELLOW
            graphics.fillRect(107, 80, 18, 18)
            graphics.color = Color.MAGENTA
            graphics.fillRect(144, 80, 18, 18)
            graphics.color = Color(255, 255, 255)
            graphics.fillRect(95 + tick * 10, 170, 8, 8)
            graphics.color = Color(10, 10, 10)
            graphics.fillRect(210, 185, 20, 12)
        }
    }

    private const val TITLE = "Spectre public crop same-title"
    private const val CROP_W = 220
    private const val CROP_H = 140
}

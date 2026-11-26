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
import java.util.concurrent.TimeUnit
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
        val decoyReveal = Path.of(requireNotNull(System.getenv("DECOY_REVEAL")))
        val receipts = mutableListOf<String>()
        var failure: Throwable? = null
        try {
            listOf(
                Case("red-decorated", Color(220, 20, 30), true),
                Case("green-undecorated", Color(20, 190, 45), false),
            ).forEach {
                try {
                    runCase(it, artifacts, decoyPid, decoyReveal, receipts)
                } catch (problem: Throwable) {
                    receipts += receipt(it.name, "failed", false, "actual" to (problem.message ?: problem.javaClass.name))
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

    private fun runCase(case: Case, artifacts: Path, decoyPid: Long, decoyReveal: Path, receipts: MutableList<String>) {
        val panel = PatternPanel(case.color)
        val frame = onEdt {
            JFrame(TITLE).apply {
                isUndecorated = !case.decorated
                contentPane = panel
                setBounds(200, 200, 480, 320)
                isAutoRequestFocus = false
                focusableWindowState = false
                isVisible = true
            }
        }
        try {
            Thread.sleep(800)
            // The colored target must exist before the normal blue decoy is mapped. This avoids
            // Mutter's startup-focus prevention race, including when green replaces red.
            if (!Files.exists(decoyReveal)) Files.writeString(decoyReveal, "reveal\n")
            val decoyXid = findOwnedDecoy(decoyPid)
            Files.writeString(artifacts.resolve("decoy-identity.json"),
                "{\"expectedPid\":$decoyPid,\"actualPid\":${commandLong("xdotool", "getwindowpid", decoyXid.toString())},\"expectedXid\":$decoyXid}\n")
            val focusStarted = System.nanoTime()
            focusDecoy(decoyPid, decoyXid)
            val focusNs = System.nanoTime() - focusStarted
            verifyOverlapAndStacking(frame, decoyPid, decoyXid)
            receipts += receipt(case.name, "blue-focus-acquired", true,
                "expectedPid" to decoyPid, "actualPid" to commandLong("xdotool", "getwindowpid", decoyXid.toString()),
                "expectedXid" to decoyXid, "actualXid" to activeXid(), "focusElapsedNs" to focusNs)
            val crop = onEdt {
                val top = frame.locationOnScreen
                val content = panel.locationOnScreen
                // This conversion is intentional: cropInWindow is relative to the AWT top-level,
                // not to its content panel. It therefore includes real decoration insets.
                Rectangle(content.x - top.x + 83, content.y - top.y + 61, CROP_W, CROP_H)
            }
            val transform = frame.graphicsConfiguration.defaultTransform
            val insets = frame.insets
            requireFocused(decoyPid, decoyXid)
            receipts += receipt(case.name, "before-still-api", true, "expectedXid" to decoyXid, "actualXid" to activeXid())
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
            receipts += receipt(case.name, "still", true, "expectedXid" to decoyXid, "actualXid" to activeXid())

            val movie = artifacts.resolve("${case.name}-crop.mp4")
            movie.deleteIfExists()
            val beforeFocus = requireFocused(decoyPid, decoyXid)
            val handle = AutoRecorder().startWindow(
                window = frame.asTitledWindow(), output = movie, options = RecordingOptions(),
                cropInWindow = crop, scaleX = transform.scaleX, scaleY = transform.scaleY,
            )
            // Duration begins only after the public API has returned its live handle.
            val started = System.nanoTime()
            receipts += receipt(case.name, "recording-handle-returned", true,
                "expectedXid" to decoyXid, "actualXid" to activeXid())
            var tick = 0
            while (System.nanoTime() - started < 3_000_000_000L) {
                onEdt { panel.tick = tick; panel.repaint() }
                tick++
                Thread.sleep(120)
                val focusCheckStarted = System.nanoTime()
                val actual = requireFocused(decoyPid, decoyXid)
                receipts += receipt(case.name, "recording-focus-observation", true,
                    "expectedXid" to decoyXid, "actualXid" to actual,
                    "focusElapsedNs" to (System.nanoTime() - focusCheckStarted), "tick" to tick)
            }
            handle.stop()
            val ended = System.nanoTime()
            check(ended - started >= 3_000_000_000L)
            val afterFocus = requireFocused(decoyPid, decoyXid)
            check(beforeFocus == afterFocus)
            receipts += receipt(case.name, "video", true, "expectedXid" to decoyXid,
                "actualXid" to afterFocus, "focusElapsedNs" to (ended - started))
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
        repeat(15) {
            // --sync is intentionally omitted: it can wait forever under Mutter. Each command is
            // bounded and targets only the independently PID-verified blue helper XID.
            command("xdotool", "windowactivate", xid.toString())
            command("xdotool", "windowraise", xid.toString())
            Thread.sleep(100)
            if (runCatching { requireFocused(pid, xid) }.isSuccess) return
        }
        val actual = runCatching { activeXid() }.getOrNull()
        error("could not acquire verified decoy focus: expectedPid=$pid expectedXid=$xid actualXid=$actual")
    }

    private fun requireFocused(pid: Long, xid: Long): Long {
        check(commandLong("xdotool", "getactivewindow") == xid)
        check(activeXid() == xid)
        check(commandLong("xdotool", "getwindowpid", xid.toString()) == pid)
        return xid
    }

    private fun findOwnedDecoy(pid: Long): Long {
        repeat(100) {
            val ids = command("xdotool", "search", "--all", "--name", TITLE, allowFailure = true)
                .lineSequence().mapNotNull(String::toLongOrNull).distinct().filter { xid ->
                    runCatching { commandLong("xdotool", "getwindowpid", xid.toString()) == pid }.getOrDefault(false)
                }.toList()
            if (ids.size == 1) return ids.single()
            Thread.sleep(50)
        }
        error("expected exactly one PID-owned blue decoy for pid=$pid")
    }

    private fun activeXid(): Long {
        val value = command("xprop", "-root", "_NET_ACTIVE_WINDOW")
            .substringAfter("#").trim().removePrefix("0x")
        return value.toLong(16)
    }

    private fun verifyOverlapAndStacking(frame: JFrame, decoyPid: Long, decoyXid: Long) {
        val targetXid = command("xdotool", "search", "--all", "--pid", ProcessHandle.current().pid().toString(), "--name", TITLE)
            .lineSequence().mapNotNull(String::toLongOrNull).single()
        check(commandLong("xdotool", "getwindowpid", targetXid.toString()) == ProcessHandle.current().pid())
        check(commandLong("xdotool", "getwindowpid", decoyXid.toString()) == decoyPid)
        val stacking = command("xprop", "-root", "_NET_CLIENT_LIST_STACKING")
        fun position(xid: Long) = stacking.lowercase().indexOf("0x${xid.toString(16)}")
        check(position(targetXid) >= 0 && position(decoyXid) > position(targetXid))
        val target = onEdt { frame.bounds }
        val decoyGeometry = command("xdotool", "getwindowgeometry", "--shell", decoyXid.toString())
            .lineSequence().mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }.associate { it[0] to it[1].toInt() }
        val decoy = Rectangle(decoyGeometry.getValue("X"), decoyGeometry.getValue("Y"), decoyGeometry.getValue("WIDTH"), decoyGeometry.getValue("HEIGHT"))
        check(target.intersects(decoy) && target.intersection(decoy).width >= 400 && target.intersection(decoy).height >= 250)
    }

    private fun command(vararg args: String, allowFailure: Boolean = false): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        check(process.waitFor(2, TimeUnit.SECONDS)) { "timed out: ${args.joinToString(" ")}" }
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!allowFailure) check(process.exitValue() == 0) { "failed (${process.exitValue()}): ${args.joinToString(" ")}: $output" }
        return output
    }

    private fun commandLong(vararg args: String) = command(*args).toLong()

    private fun receipt(case: String, phase: String, ok: Boolean, vararg fields: Pair<String, Any>): String {
        fun value(item: Any) = if (item is Number || item is Boolean) item.toString() else "\"${item.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
        return (listOf("case" to case, "phase" to phase, "ok" to ok) + fields)
            .joinToString(prefix = "{", postfix = "}") { (key, item) -> "\"$key\":${value(item)}" }
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

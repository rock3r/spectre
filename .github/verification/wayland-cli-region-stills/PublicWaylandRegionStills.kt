package verification

import dev.sebastiano.spectre.recording.LinuxNativeScreenshotter
import java.awt.Color
import java.awt.Graphics
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Attended-seat verification of the public Linux REGION still API. */
object PublicWaylandRegionStills {
    private val artifacts = Path.of(requireNotNull(System.getenv("SMOKE_ARTIFACTS")))
    private val receipts = artifacts.resolve("region-java-receipts.jsonl")
    private val stages = artifacts.resolve("region-stage-boundaries.jsonl")
    private val socket = Path.of(requireNotNull(System.getenv("SPECTRE_REGION_SESSION_SOCKET")))
    private val expectedOwner =
        requireNotNull(System.getenv("SPECTRE_REGION_SESSION_OWNER_PID")).toLong()
    private val first = Rectangle(137, 91, 311, 173)
    private val second = Rectangle(604, 277, 229, 191)
    private val cyan = Rectangle(169, 153, 18, 18)
    private val magenta = Rectangle(627, 368, 18, 18)
    private val red = Color(220, 25, 25)
    private val green = Color(20, 210, 40)
    private const val title = "Spectre region calibration"

    @JvmStatic
    fun main(args: Array<String>) {
        stage("process-start")
        check(System.getenv("XDG_SESSION_TYPE") == "wayland")
        val configuration =
            GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
        val screen = configuration.bounds
        val transform = configuration.defaultTransform
        geometry(screen, transform.scaleX, transform.scaleY, null)
        diagnostics("before-window")
        check(screen.x == 0 && screen.y == 0 && screen.width >= 1000 && screen.height >= 600)
        check(transform.scaleX == 1.0 && transform.scaleY == 1.0)

        val scene = Scene()
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            frame =
                JFrame(title).apply {
                    isUndecorated = true
                    contentPane = scene
                    setBounds(screen)
                    isVisible = true
                    toFront()
                }
        }
        try {
            val preMap = awtGeometry(scene)
            writeGeometry("region-pre-map-awt-geometry.json", screen, preMap)
            stage("window-mapped")
            val xid =
                command(
                        "fixture-xid.txt",
                        "xdotool",
                        "search",
                        "--sync",
                        "--onlyvisible",
                        "--pid",
                        ProcessHandle.current().pid().toString(),
                        "--name",
                        "^$title$",
                    )
                    .lineSequence()
                    .map(String::trim)
                    .first { it.matches(Regex("[0-9]+")) }
            command("seat-activate.txt", "xdotool", "windowactivate", "--sync", xid)
            command("seat-focus.txt", "xdotool", "windowfocus", "--sync", xid)
            val cursor = Point(screen.x + screen.width - 2, screen.y + screen.height - 2)
            command(
                "seat-cursor.txt",
                "xdotool",
                "mousemove",
                cursor.x.toString(),
                cursor.y.toString(),
            )
            diagnostics("focused", xid)
            val active = command("net-active-window.txt", "xprop", "-root", "_NET_ACTIVE_WINDOW")
            val activeXid = Regex("0x([0-9a-fA-F]+)").find(active)?.groupValues?.get(1)?.toLong(16)
            check(activeXid == xid.toLong()) { "fixture $xid is not active: $active" }

            SwingUtilities.invokeAndWait {
                frame.isVisible = true
                frame.toFront()
                frame.validate()
            }
            Toolkit.getDefaultToolkit().sync()
            val actual = reconcileGeometry(scene, screen, xid)
            val origin = actual.awt.location
            check(!move(first, origin).contains(cursor) && !move(second, origin).contains(cursor))
            geometry(screen, transform.scaleX, transform.scaleY, origin)

            val shared = LinuxNativeScreenshotter()
            phase(shared, scene, screen, origin, changed = false)
            val fresh = LinuxNativeScreenshotter()
            phase(shared, scene, screen, origin, changed = true, fresh = fresh)
        } finally {
            diagnostics("final")
            SwingUtilities.invokeAndWait { frame.dispose() }
            stage("process-finish")
        }
    }

    private fun phase(
        shared: LinuxNativeScreenshotter,
        scene: Scene,
        screen: Rectangle,
        origin: Point,
        changed: Boolean,
        fresh: LinuxNativeScreenshotter? = null,
    ) {
        val phase = if (changed) "green" else "red"
        SwingUtilities.invokeAndWait {
            scene.changed = changed
            scene.paintImmediately(0, 0, scene.width, scene.height)
        }
        Toolkit.getDefaultToolkit().sync()
        stage("$phase-paint-submitted")
        val settled = settleFrame(shared, screen, origin, phase, if (changed) green else red)
        val reference = settled.image
        ImageIO.write(
            reference,
            "png",
            artifacts.resolve("region-$phase-full-reference.png").toFile(),
        )
        receipt(
            "region-$phase-full-reference.png",
            "full-reference",
            settled.started,
            settled.ended,
            screen,
            reference,
        )
        stage("$phase-reference-retained")

        val firstClient = fresh ?: shared
        if (fresh != null) socketOwner("before-fresh")
        crop(
            firstClient,
            reference,
            move(first, origin),
            if (changed) "region-a-green-fresh.png" else "region-a-red.png",
            Color.CYAN,
            32,
            62,
        )
        if (fresh != null) socketOwner("after-fresh")
        crop(
            shared,
            reference,
            move(second, origin),
            if (changed) "region-b-green-repeated.png" else "region-b-red.png",
            Color.MAGENTA,
            23,
            91,
        )
        stage("$phase-regions-complete")
    }

    private fun settleFrame(
        client: LinuxNativeScreenshotter,
        screen: Rectangle,
        origin: Point,
        phase: String,
        base: Color,
    ): SettledFrame {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var attempt = 0
        var last: BufferedImage? = null
        var cyanBox: Rectangle? = null
        var magentaBox: Rectangle? = null
        val rgbObservations = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            attempt++
            val started = System.nanoTime()
            val image = client.captureRegion(screen)
            val ended = System.nanoTime()
            last = image
            val name = "region-$phase-settle-attempt-${attempt.toString().padStart(2, '0')}.png"
            ImageIO.write(image, "png", artifacts.resolve(name).toFile())
            receipt(name, "settle-attempt", started, ended, screen, image)
            cyanBox = boundingBox(image, Color.CYAN)
            magentaBox = boundingBox(image, Color.MAGENTA)
            val expectedCyan = move(cyan, origin)
            val expectedMagenta = move(magenta, origin)
            rgbObservations +=
                "{\"attempt\":$attempt,\"cyan\":${rgbAt(image, expectedCyan.x, expectedCyan.y)},\"magenta\":${rgbAt(image, expectedMagenta.x, expectedMagenta.y)},\"base\":${rgbAt(image, origin.x + 80, origin.y + 60)}}"
            val unchanged =
                image.width == screen.width &&
                    image.height == screen.height &&
                    cyanBox == expectedCyan &&
                    magentaBox == expectedMagenta &&
                    isSolid(image, expectedCyan, Color.CYAN) &&
                    isSolid(image, expectedMagenta, Color.MAGENTA) &&
                    image.getRGB(origin.x + 80, origin.y + 60) == base.rgb
            stage("$phase-frame-observed", "$name:UNCHANGED=$unchanged")
            if (unchanged) return SettledFrame(image, started, ended)
            Thread.sleep(200)
        }
        val image = checkNotNull(last)
        val complete = artifacts.resolve("region-$phase-settle-timeout-complete.png")
        ImageIO.write(image, "png", complete.toFile())
        diagnostics("$phase-timeout")
        val nativeGeometry = "region-$phase-timeout-native-geometry.txt"
        command(nativeGeometry, "xwininfo", "-root", "-stats", "-tree", required = false)
        Files.writeString(
            artifacts.resolve("region-$phase-settle-timeout.json"),
            """{"nativeGeometry":"$nativeGeometry","completePng":"${complete.fileName}","cyanBox":${boxJson(cyanBox)},"magentaBox":${boxJson(magentaBox)},"cyanScaled":${scaledJson(cyanBox, screen)},"magentaScaled":${scaledJson(magentaBox, screen)},"expectedRgb":{"cyan":${Color.CYAN.rgb},"magenta":${Color.MAGENTA.rgb},"base":${base.rgb}},"rgbObservations":${rgbObservations.joinToString(prefix = "[", postfix = "]")}}
""",
        )
        error("$phase compositor frame did not settle before deadline")
    }

    private fun crop(
        client: LinuxNativeScreenshotter,
        reference: BufferedImage,
        request: Rectangle,
        name: String,
        color: Color,
        markerX: Int,
        markerY: Int,
    ) {
        val started = System.nanoTime()
        val image = client.captureRegion(request)
        val ended = System.nanoTime()
        ImageIO.write(image, "png", artifacts.resolve(name).toFile())
        receipt(name, "public-region", started, ended, request, image)
        check(image.width == request.width && image.height == request.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val expected = reference.getRGB(request.x + x, request.y + y)
            check(image.getRGB(x, y) == expected) {
                "$name differs from retained full reference at $x,$y; crop=${image.getRGB(x, y)} reference=$expected"
            }
        }
        solid(image, Rectangle(markerX, markerY, 18, 18), color, "$name marker")
        check(image.getRGB(markerX + 70, markerY) != color.rgb)
        check(image.getRGB(markerX, markerY + 45) != color.rgb)
    }

    private fun receipt(
        name: String,
        kind: String,
        started: Long,
        ended: Long,
        request: Rectangle,
        image: BufferedImage,
    ) {
        Files.writeString(
            receipts,
            """{"name":"$name","kind":"$kind","startedNs":$started,"endedNs":$ended,"request":{"x":${request.x},"y":${request.y},"width":${request.width},"height":${request.height}},"returned":{"width":${image.width},"height":${image.height}}}
""",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun solid(image: BufferedImage, area: Rectangle, expected: Color, label: String) {
        check(isSolid(image, area, expected)) {
            "$label is not an exact solid ${area.width}x${area.height} box at $area"
        }
    }

    private fun isSolid(image: BufferedImage, area: Rectangle, expected: Color): Boolean {
        if (
            area.x < 0 ||
                area.y < 0 ||
                area.x + area.width > image.width ||
                area.y + area.height > image.height
        )
            return false
        for (y in area.y until area.y + area.height) for (x in area.x until area.x + area.width) {
            if (image.getRGB(x, y) != expected.rgb) return false
        }
        return true
    }

    private fun boundingBox(image: BufferedImage, color: Color): Rectangle? {
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y) == color.rgb) {
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        return if (maxX < 0) null else Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun geometry(screen: Rectangle, sx: Double, sy: Double, origin: Point?) {
        Files.writeString(
            artifacts.resolve("region-app-geometry.json"),
            """{"units":"AWT user-space pixels","device":{"x":${screen.x},"y":${screen.y},"width":${screen.width},"height":${screen.height}},"sceneOrigin":${origin?.let { "{\"x\":${it.x},\"y\":${it.y}}" } ?: "null"},"first":{"x":${first.x},"y":${first.y},"width":${first.width},"height":${first.height}},"second":{"x":${second.x},"y":${second.y},"width":${second.width},"height":${second.height}},"markers":{"cyan":{"x":${cyan.x},"y":${cyan.y},"width":18,"height":18},"magenta":{"x":${magenta.x},"y":${magenta.y},"width":18,"height":18}},"scaleX":$sx,"scaleY":$sy}
""",
        )
    }

    private fun reconcileGeometry(scene: Scene, screen: Rectangle, xid: String): ActualGeometry {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val observations = mutableListOf<ActualGeometry>()
        var consecutive = 0
        var previous: ActualGeometry? = null
        while (System.nanoTime() < deadline) {
            Toolkit.getDefaultToolkit().sync()
            val awt = awtGeometry(scene)
            val observationNumber = observations.size + 1
            val nativeOutput =
                command(
                    "fixture-geometry-${observationNumber.toString().padStart(2, '0')}-xwininfo.txt",
                    "xwininfo",
                    "-id",
                    xid,
                    "-stats",
                )
            val native = parseNativeGeometry(nativeOutput)
            val actual = ActualGeometry(awt, native)
            observations += actual
            val agrees =
                awt == native &&
                    screen.contains(awt.location) &&
                    awt.width > 0 &&
                    awt.height > 0 &&
                    awt.x + awt.width <= screen.x + screen.width &&
                    awt.y + awt.height <= screen.y + screen.height
            consecutive = if (agrees && actual == previous) consecutive + 1 else if (agrees) 1 else 0
            previous = actual
            if (consecutive >= 3) {
                writeActualGeometry(screen, observations, actual)
                return actual
            }
            Thread.sleep(200)
        }
        writeActualGeometry(screen, observations, null)
        error("AWT and native per-XID geometry did not agree for three consistent observations")
    }

    private fun awtGeometry(scene: Scene): Rectangle {
        var result: Rectangle? = null
        SwingUtilities.invokeAndWait {
            val location = scene.locationOnScreen
            result = Rectangle(location.x, location.y, scene.width, scene.height)
        }
        return checkNotNull(result)
    }

    private fun parseNativeGeometry(output: String): Rectangle {
        fun value(label: String): Int =
            requireNotNull(Regex("(?m)^\\s*$label:\\s*(-?\\d+)\\s*$").find(output)) {
                    "xwininfo omitted $label"
                }
                .groupValues[1]
                .toInt()
        return Rectangle(
            value("Absolute upper-left X"),
            value("Absolute upper-left Y"),
            value("Width"),
            value("Height"),
        )
    }

    private fun writeGeometry(name: String, screen: Rectangle, geometry: Rectangle) {
        Files.writeString(
            artifacts.resolve(name),
            """{"screen":${boxJson(screen)},"awt":${boxJson(geometry)}}
""",
        )
    }

    private fun writeActualGeometry(
        screen: Rectangle,
        observations: List<ActualGeometry>,
        agreed: ActualGeometry?,
    ) {
        val values =
            observations.joinToString(prefix = "[", postfix = "]") {
                "{\"awt\":${boxJson(it.awt)},\"nativeXwininfo\":${boxJson(it.native)}}"
            }
        Files.writeString(
            artifacts.resolve("region-final-actual-geometry.json"),
            """{"screen":${boxJson(screen)},"observations":$values,"agreed":${agreed?.let { "{\"awt\":${boxJson(it.awt)},\"nativeXwininfo\":${boxJson(it.native)}}" } ?: "null"}}
""",
        )
    }

    private fun diagnostics(label: String, xid: String? = null) {
        val commands =
            mutableListOf(
                listOf("xrandr", "--current", "--verbose"),
                listOf("xprop", "-root", "_NET_ACTIVE_WINDOW", "_NET_CURRENT_DESKTOP"),
                listOf(
                    "gdbus",
                    "call",
                    "--session",
                    "--dest",
                    "org.gnome.Mutter.DisplayConfig",
                    "--object-path",
                    "/org/gnome/Mutter/DisplayConfig",
                    "--method",
                    "org.gnome.Mutter.DisplayConfig.GetCurrentState",
                ),
                listOf(
                    "gdbus",
                    "call",
                    "--session",
                    "--dest",
                    "org.freedesktop.DBus",
                    "--object-path",
                    "/org/freedesktop/DBus",
                    "--method",
                    "org.freedesktop.DBus.ListNames",
                ),
                listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"),
                listOf("pw-dump"),
            )
        if (xid != null) {
            commands += listOf("xwininfo", "-id", xid, "-stats", "-tree")
            commands +=
                listOf(
                    "xprop",
                    "-id",
                    xid,
                    "_NET_WM_PID",
                    "WM_CLASS",
                    "_NET_WM_WINDOW_TYPE",
                    "_NET_WM_STATE",
                )
        } else {
            commands += listOf("xwininfo", "-root", "-stats", "-tree")
        }
        commands.forEachIndexed { index, parts ->
            command(
                "host-$label-$index-${parts.first()}.txt",
                *parts.toTypedArray(),
                required = false,
            )
        }
    }

    private fun socketOwner(label: String) {
        val started = System.nanoTime()
        val error = artifacts.resolve("socket-owner-$label.stderr")
        val standard = artifacts.resolve("socket-owner-$label.stdout")
        val process =
            ProcessBuilder("fuser", socket.toString())
                .redirectError(error.toFile())
                .redirectOutput(standard.toFile())
                .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        val exit = if (finished) process.exitValue() else null
        val output = Files.readString(standard)
        val owners = output.split(Regex("\\s+")).mapNotNull(String::toLongOrNull)
        val live = owners.map { Files.isDirectory(Path.of("/proc/$it")) }
        val valid = exit == 0 && owners == listOf(expectedOwner) && live == listOf(true)
        Files.writeString(
            receipts,
            """{"name":"$label","kind":"socket-owner","startedNs":$started,"endedNs":${System.nanoTime()},"socket":"${json(socket.toString())}","expectedOwnerPid":$expectedOwner,"fuserExit":${exit ?: "null"},"owners":${owners.joinToString(prefix = "[", postfix = "]")},"ownersLive":${live.joinToString(prefix = "[", postfix = "]")},"valid":$valid}
""",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        check(valid) {
            "exact session socket owner mismatch for $label: expected $expectedOwner, owners=$owners, live=$live, fuserExit=$exit"
        }
    }

    private fun json(value: String) =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun command(name: String, vararg args: String, required: Boolean = true): String {
        stage("command-start", name)
        val path = artifacts.resolve(name)
        Files.writeString(path, "startedUtc=${Instant.now()}\n")
        val process =
            ProcessBuilder(*args)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(path.toFile()))
                .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) {
                "could not kill timed-out command for $name"
            }
        }
        Files.writeString(
            path,
            "finished=$finished\nexit=${if (finished) process.exitValue() else "timeout"}\n",
            StandardOpenOption.APPEND,
        )
        val output = sanitize(Files.readString(path))
        Files.writeString(path, output)
        stage("command-finish", name)
        check(!required || (finished && process.exitValue() == 0)) { "command failed; see $name" }
        return output
    }

    private fun sanitize(text: String): String =
        text.replace(
            Regex("(?i)(authorization|bearer|restore[_ -]?token)([\\\"=: ]+)[^\\s\\\",}]+"),
            "$1$2<redacted>",
        )

    private fun stage(name: String, artifact: String? = null) {
        Files.writeString(
            stages,
            """{"stage":"$name","monotonicNs":${System.nanoTime()},"utc":"${Instant.now()}"${artifact?.let { ",\"artifact\":\"$it\"" } ?: ""}}
""",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun move(area: Rectangle, origin: Point) =
        Rectangle(area.x + origin.x, area.y + origin.y, area.width, area.height)

    private fun boxJson(box: Rectangle?) =
        box?.let { "{\"x\":${it.x},\"y\":${it.y},\"width\":${it.width},\"height\":${it.height}}" }
            ?: "null"

    private fun scaledJson(box: Rectangle?, screen: Rectangle) =
        box?.let {
            "{\"x\":${it.x.toDouble() / screen.width},\"y\":${it.y.toDouble() / screen.height},\"width\":${it.width.toDouble() / screen.width},\"height\":${it.height.toDouble() / screen.height}}"
        } ?: "null"

    private fun rgbAt(image: BufferedImage, x: Int, y: Int): Int? =
        if (x in 0 until image.width && y in 0 until image.height) image.getRGB(x, y) else null

    private data class SettledFrame(val image: BufferedImage, val started: Long, val ended: Long)

    private data class ActualGeometry(val awt: Rectangle, val native: Rectangle)

    private class Scene : JPanel() {
        @Volatile var changed = false

        override fun paintComponent(graphics: Graphics) {
            graphics.color = if (changed) green else red
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color(245, 210, 20)
            graphics.fillRect(0, 0, width, 29)
            graphics.color = Color(35, 65, 230)
            graphics.fillRect(0, 29, 23, height - 29)
            graphics.color = Color.CYAN
            graphics.fillRect(cyan.x, cyan.y, cyan.width, cyan.height)
            graphics.color = Color.MAGENTA
            graphics.fillRect(magenta.x, magenta.y, magenta.width, magenta.height)
            graphics.color = Color(255, 130, 20)
            graphics.fillRect(1000, 510, 73, 41)
        }
    }
}

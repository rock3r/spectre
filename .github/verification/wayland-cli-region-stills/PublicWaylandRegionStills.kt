package verification

import dev.sebastiano.spectre.recording.LinuxNativeScreenshotter
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Attended-seat verification of the public Linux REGION still API. */
object PublicWaylandRegionStills {
    private val artifacts = Path.of(requireNotNull(System.getenv("SMOKE_ARTIFACTS")))
    private val receipts = artifacts.resolve("region-java-receipts.jsonl")
    private val first = Rectangle(137, 91, 311, 173)
    private val second = Rectangle(604, 277, 229, 191)

    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getenv("XDG_SESSION_TYPE") == "wayland")
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .defaultConfiguration.bounds
        val transform = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
            .defaultConfiguration.defaultTransform
        check(bounds.x == 0 && bounds.y == 0 && bounds.width >= 1000 && bounds.height >= 600) {
            "unexpected device bounds: $bounds"
        }
        check(transform.scaleX == 1.0 && transform.scaleY == 1.0) {
            "fixture requires settled 1:1 AWT device pixels, got $transform"
        }
        val scene = Scene()
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            frame = JFrame("Spectre region calibration").apply {
                isUndecorated = true
                contentPane = scene
                setBounds(bounds)
                isVisible = true
                toFront()
            }
        }
        try {
            settle(scene, Color.RED, first.x + 40, first.y + 70)
            val shared = LinuxNativeScreenshotter()
            capture(shared, first, "region-a-red.png", Color.RED, Color.CYAN, 40, 70)
            capture(shared, second, "region-b-red.png", Color.RED, Color.MAGENTA, 31, 99)

            SwingUtilities.invokeAndWait { scene.green = true; scene.repaint() }
            settle(scene, Color.GREEN, first.x + 40, first.y + 70)
            // A fresh public client must join the same held grant, while the old client remains valid.
            capture(LinuxNativeScreenshotter(), first, "region-a-green-fresh.png", Color.GREEN, Color.CYAN, 40, 70)
            capture(shared, second, "region-b-green-repeated.png", Color.GREEN, Color.MAGENTA, 31, 99)
            Files.writeString(
                artifacts.resolve("region-app-geometry.json"),
                """{"device":{"x":${bounds.x},"y":${bounds.y},"width":${bounds.width},"height":${bounds.height}},"first":{"x":${first.x},"y":${first.y},"width":${first.width},"height":${first.height}},"second":{"x":${second.x},"y":${second.y},"width":${second.width},"height":${second.height}},"scaleX":${frame.graphicsConfiguration.defaultTransform.scaleX},"scaleY":${frame.graphicsConfiguration.defaultTransform.scaleY}}\n""",
            )
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun settle(scene: Scene, expected: Color, x: Int, y: Int) {
        Toolkit.getDefaultToolkit().sync()
        val robot = Robot()
        repeat(100) {
            if (near(robot.getPixelColor(x, y), expected)) {
                Thread.sleep(350)
                Toolkit.getDefaultToolkit().sync()
                if (near(robot.getPixelColor(x, y), expected)) return
            }
            Thread.sleep(50)
        }
        error("compositor never exposed ${expected.rgb} at $x,$y")
    }

    private fun capture(client: LinuxNativeScreenshotter, region: Rectangle, name: String,
                        base: Color, landmark: Color, landmarkX: Int, landmarkY: Int) {
        val started = System.nanoTime()
        val image = client.captureRegion(region)
        val ended = System.nanoTime()
        check(image.width == region.width && image.height == region.height)
        requireColor(image, landmarkX, landmarkY, landmark, "$name landmark")
        requireColor(image, 80, 60, base, "$name interior")
        // Offset negatives catch a stale crop from another origin; the landmarks are deliberately local.
        check(!near(Color(image.getRGB(landmarkX + 70, landmarkY)), landmark))
        check(!near(Color(image.getRGB(landmarkX, landmarkY + 45)), landmark))
        val black = (0 until image.width step 7).sumOf { x ->
            (0 until image.height step 7).count { y -> Color(image.getRGB(x, y)).let { it.red < 8 && it.green < 8 && it.blue < 8 } }
        }
        check(black == 0) { "$name contains black calibration samples" }
        ImageIO.write(image, "png", artifacts.resolve(name).toFile())
        Files.writeString(receipts,
            """{"name":"$name","startedNs":$started,"endedNs":$ended,"x":${region.x},"y":${region.y},"width":${image.width},"height":${image.height},"base":${base.rgb},"landmark":${landmark.rgb}}\n""",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private fun requireColor(image: BufferedImage, x: Int, y: Int, color: Color, label: String) {
        check(near(Color(image.getRGB(x, y)), color)) { "$label mismatch at $x,$y" }
    }

    private fun near(actual: Color, expected: Color) =
        kotlin.math.abs(actual.red - expected.red) < 20 &&
            kotlin.math.abs(actual.green - expected.green) < 20 &&
            kotlin.math.abs(actual.blue - expected.blue) < 20

    private class Scene : JPanel() {
        @Volatile var green = false
        override fun paintComponent(graphics: Graphics) {
            val g = graphics as Graphics2D
            g.color = if (green) Color(20, 210, 40) else Color(220, 25, 25)
            g.fillRect(0, 0, width, height)
            g.color = Color(245, 210, 20)
            g.fillRect(0, 0, width, 29)
            g.color = Color(35, 65, 230)
            g.fillRect(0, 29, 23, height - 29)
            g.color = Color.CYAN
            g.fillRect(first.x + 32, first.y + 62, 18, 18)
            g.color = Color.MAGENTA
            g.fillRect(second.x + 23, second.y + 91, 18, 18)
            g.color = Color(255, 130, 20)
            g.fillRect(1000, 510, 73, 41)
        }
    }
}

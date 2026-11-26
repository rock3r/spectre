package verification

import dev.sebastiano.spectre.recording.LinuxNativeScreenshotter
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
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
    private val firstLayout = Rectangle(137, 91, 311, 173)
    private val secondLayout = Rectangle(604, 277, 229, 191)
    private val red = Color(220, 25, 25)
    private val green = Color(20, 210, 40)

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
            render(scene, green = false)
            val origin = scene.locationOnScreen
            val first = Rectangle(origin.x + firstLayout.x, origin.y + firstLayout.y,
                firstLayout.width, firstLayout.height)
            val second = Rectangle(origin.x + secondLayout.x, origin.y + secondLayout.y,
                secondLayout.width, secondLayout.height)
            val shared = LinuxNativeScreenshotter()
            capture(shared, first, firstLayout, origin, "region-a-red.png", red, Color.CYAN, 40, 70)
            capture(shared, second, secondLayout, origin, "region-b-red.png", red, Color.MAGENTA, 31, 99)

            render(scene, green = true)
            // A fresh public client must join the same held grant, while the old client remains valid.
            capture(LinuxNativeScreenshotter(), first, firstLayout, origin, "region-a-green-fresh.png", green, Color.CYAN, 40, 70)
            capture(shared, second, secondLayout, origin, "region-b-green-repeated.png", green, Color.MAGENTA, 31, 99)
            check(red != green)
            Files.writeString(
                artifacts.resolve("region-app-geometry.json"),
                """{"device":{"x":${bounds.x},"y":${bounds.y},"width":${bounds.width},"height":${bounds.height}},"sceneOrigin":{"x":${origin.x},"y":${origin.y}},"first":{"x":${first.x},"y":${first.y},"width":${first.width},"height":${first.height}},"second":{"x":${second.x},"y":${second.y},"width":${second.width},"height":${second.height}},"scaleX":${frame.graphicsConfiguration.defaultTransform.scaleX},"scaleY":${frame.graphicsConfiguration.defaultTransform.scaleY}}""" + "\n",
            )
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun render(scene: Scene, green: Boolean) {
        SwingUtilities.invokeAndWait {
            scene.green = green
            scene.paintImmediately(0, 0, scene.width, scene.height)
        }
        // Flush the completed AWT/XWayland paint without asking Robot (and therefore a second
        // Wayland screenshot portal) to inspect the screen.
        Toolkit.getDefaultToolkit().sync()
    }

    private fun capture(client: LinuxNativeScreenshotter, region: Rectangle, layout: Rectangle,
                        origin: java.awt.Point, name: String,
                        base: Color, landmark: Color, landmarkX: Int, landmarkY: Int) {
        val started = System.nanoTime()
        val image = client.captureRegion(region)
        val ended = System.nanoTime()
        // Preserve the genuine public capture even when a following calibration assertion fails.
        ImageIO.write(image, "png", artifacts.resolve(name).toFile())
        check(image.width == region.width && image.height == region.height)
        val observedInterior = Color(image.getRGB(80, 60))
        val observedLandmark = Color(image.getRGB(landmarkX, landmarkY))
        requireColor(image, landmarkX, landmarkY, landmark, "$name landmark")
        requireColor(image, 80, 60, base, "$name interior")
        // Offset negatives catch a stale crop from another origin; the landmarks are deliberately local.
        check(!near(Color(image.getRGB(landmarkX + 70, landmarkY)), landmark))
        check(!near(Color(image.getRGB(landmarkX, landmarkY + 45)), landmark))
        val black = (0 until image.width step 7).sumOf { x ->
            (0 until image.height step 7).count { y -> Color(image.getRGB(x, y)).let { it.red < 8 && it.green < 8 && it.blue < 8 } }
        }
        check(black == 0) { "$name contains black calibration samples" }
        Files.writeString(receipts,
            """{"name":"$name","startedNs":$started,"endedNs":$ended,"sceneOrigin":{"x":${origin.x},"y":${origin.y}},"layout":{"x":${layout.x},"y":${layout.y},"width":${layout.width},"height":${layout.height}},"region":{"x":${region.x},"y":${region.y},"width":${image.width},"height":${image.height}},"expectedBase":${base.rgb},"expectedLandmark":${landmark.rgb},"observedInterior":{"red":${observedInterior.red},"green":${observedInterior.green},"blue":${observedInterior.blue}},"observedLandmark":{"red":${observedLandmark.red},"green":${observedLandmark.green},"blue":${observedLandmark.blue}}}""" + "\n",
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
            g.color = if (green) PublicWaylandRegionStills.green else red
            g.fillRect(0, 0, width, height)
            g.color = Color(245, 210, 20)
            g.fillRect(0, 0, width, 29)
            g.color = Color(35, 65, 230)
            g.fillRect(0, 29, 23, height - 29)
            g.color = Color.CYAN
            g.fillRect(firstLayout.x + 32, firstLayout.y + 62, 18, 18)
            g.color = Color.MAGENTA
            g.fillRect(secondLayout.x + 23, secondLayout.y + 91, 18, 18)
            g.color = Color(255, 130, 20)
            g.fillRect(1000, 510, 73, 41)
        }
    }
}

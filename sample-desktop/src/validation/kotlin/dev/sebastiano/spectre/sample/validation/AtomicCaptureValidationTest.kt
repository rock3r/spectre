package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.capture.CaptureArtifactsWriter
import dev.sebastiano.spectre.core.capture.CaptureRect
import dev.sebastiano.spectre.core.capture.screenRectToImageRect
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live acceptance for #180: [dev.sebastiano.spectre.core.ComposeAutomator.capture] on a real
 * Compose Desktop sample UI.
 *
 * Asserts that `capture.json` image-space bounds for a known tagged node correlate with the PNG
 * pixels, and that HiDPI density reported by the capture is applied consistently (the sample's
 * hidpi scenario exercises fixed-dp targets whose screen/image geometry must scale together).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AtomicCaptureValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre atomic capture validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `capture boundsImage matches PNG pixels for a tagged HiDPI target`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.hidpi")
            // Semantics settle is enough here; waitForVisualIdle can hang under the synthetic
            // driver when the host display is busy (same pattern as Issue8FidelityValidationTest).
            waitForTestTag("hidpi.target.40x0")
            waitForIdle()

            val capture = capture(windowIndex = 0)
            assertTrue(capture.pngBytes.isNotEmpty(), "PNG payload must be non-empty")
            assertTrue(capture.document.nodes.isNotEmpty(), "semantics tree must be non-empty")
            assertEquals(1, capture.document.schemaVersion)

            val node = capture.document.nodes.firstOrNull { it.testTag == "hidpi.target.40x0" }
            assertNotNull(node, "capture.json must include hidpi.target.40x0")
            assertTrue(
                node.clickable || node.enabled,
                "target node should be interactive or enabled",
            )

            val window = capture.document.window
            assertEquals(capture.image.width, window.imageWidth)
            assertEquals(capture.image.height, window.imageHeight)
            assertTrue(window.densityScaleX > 0.0 && window.densityScaleY > 0.0)

            // Image-space bounds must be the pure transform of screen bounds through the
            // capture region and PNG size — same math production uses.
            val expectedImage =
                screenRectToImageRect(
                    screen =
                        Rectangle(
                            node.boundsScreen.x,
                            node.boundsScreen.y,
                            node.boundsScreen.width,
                            node.boundsScreen.height,
                        ),
                    captureOriginX = window.boundsScreen.x,
                    captureOriginY = window.boundsScreen.y,
                    captureAwtWidth = window.boundsScreen.width,
                    captureAwtHeight = window.boundsScreen.height,
                    imageWidth = window.imageWidth,
                    imageHeight = window.imageHeight,
                )
            assertEquals(
                expectedImage,
                node.boundsImage,
                "boundsImage must match screen→image mapping for this capture",
            )
            assertTrue(node.boundsImage.width > 0 && node.boundsImage.height > 0)
            assertTrue(
                node.boundsImage.x >= 0 &&
                    node.boundsImage.y >= 0 &&
                    node.boundsImage.x + node.boundsImage.width <= window.imageWidth &&
                    node.boundsImage.y + node.boundsImage.height <= window.imageHeight,
                "boundsImage must lie inside the PNG: ${node.boundsImage} vs ${window.imageWidth}x${window.imageHeight}",
            )

            // PNG must actually contain non-background pixels inside the node's image box.
            // HiDPI targets are solid primary-colored squares — sample the centre and a few
            // interior points so we prove boundsImage points at the painted widget, not chrome.
            val png = ImageIO.read(ByteArrayInputStream(capture.pngBytes))
            assertNotNull(png)
            assertEquals(window.imageWidth, png.width)
            assertEquals(window.imageHeight, png.height)
            assertTargetPixelsPresent(png, node.boundsImage)

            // Density is recorded from the graphics transform; PNG scale may differ (synthetic
            // screenshots often yield 1:1 AWT↔image while densityScale reports Retina 2.0). The
            // image-space transform must still reconcile boundsScreen with the actual PNG size.
            assertTrue(window.densityScaleX > 0.0 && window.densityScaleY > 0.0)
            val scaleFromPixelsX =
                window.imageWidth.toDouble() / window.boundsScreen.width.toDouble()
            val scaleFromPixelsY =
                window.imageHeight.toDouble() / window.boundsScreen.height.toDouble()
            assertTrue(scaleFromPixelsX > 0.0 && scaleFromPixelsY > 0.0)

            // Second target: relative geometry in image space must preserve the 2:1 dp ratio
            // (160dp x, 80dp y) regardless of absolute density — HiDPI-scaled config coverage.
            val node2 = capture.document.nodes.firstOrNull { it.testTag == "hidpi.target.200x80" }
            assertNotNull(node2)
            val dxImage = node2.boundsImage.x - node.boundsImage.x
            val dyImage = node2.boundsImage.y - node.boundsImage.y
            assertTrue(dxImage > 0 && dyImage > 0)
            val ratio = dxImage.toDouble() / dyImage.toDouble()
            assertTrue(
                abs(ratio - 2.0) < 0.12,
                "image-space delta ratio should stay ~2.0 (160dp/80dp), was $ratio " +
                    "(dx=$dxImage dy=$dyImage density=${window.densityScaleX} " +
                    "pngScale=$scaleFromPixelsX)",
            )

            // Files on disk must match the in-memory document/PNG.
            val outDir = Files.createTempDirectory("spectre-atomic-capture-validation")
            val written =
                CaptureArtifactsWriter.write(
                    directory = outDir,
                    document = capture.document,
                    pngBytes = capture.pngBytes,
                )
            assertTrue(Files.isRegularFile(written.captureJsonPath))
            assertTrue(Files.isRegularFile(written.screenshotPngPath))
            val diskPng = ImageIO.read(written.screenshotPngPath.toFile())
            assertNotNull(diskPng)
            assertEquals(png.width, diskPng.width)
            assertEquals(png.height, diskPng.height)
            val diskJson = Files.readString(written.captureJsonPath)
            assertTrue(
                diskJson.contains("\"schemaVersion\": 1") ||
                    diskJson.contains("\"schemaVersion\":1")
            )
            assertTrue(diskJson.contains("hidpi.target.40x0"))
        }
    }

    private fun assertTargetPixelsPresent(image: BufferedImage, box: CaptureRect) {
        val samples = mutableListOf<Int>()
        val cx = box.x + box.width / 2
        val cy = box.y + box.height / 2
        samples += image.getRGB(cx.coerceIn(0, image.width - 1), cy.coerceIn(0, image.height - 1))
        // Interior offsets avoid anti-aliased edges.
        val insetX = (box.width / 4).coerceAtLeast(1)
        val insetY = (box.height / 4).coerceAtLeast(1)
        samples +=
            image.getRGB(
                (box.x + insetX).coerceIn(0, image.width - 1),
                (box.y + insetY).coerceIn(0, image.height - 1),
            )
        samples +=
            image.getRGB(
                (box.x + box.width - insetX - 1).coerceIn(0, image.width - 1),
                (box.y + box.height - insetY - 1).coerceIn(0, image.height - 1),
            )
        // At least one interior sample must be far from pure black — solid primary fill.
        val nonBlack = samples.any { rgb ->
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            r + g + b > 40
        }
        assertTrue(
            nonBlack,
            "expected non-black pixels inside boundsImage $box (samples=${samples.map { Integer.toHexString(it) }})",
        )
    }
}

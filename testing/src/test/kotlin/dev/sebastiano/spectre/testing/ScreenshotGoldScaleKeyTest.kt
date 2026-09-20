package dev.sebastiano.spectre.testing

import androidx.compose.ui.awt.ComposePanel
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScreenshotGoldScaleKeyTest {

    @Test
    fun `scale key uses the matching capture surface instead of the fallback`() {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val key =
            currentScaleKey(
                image = image,
                surfaces =
                    listOf(
                        CaptureSurfaceScale(
                            pixelWidth = 200,
                            pixelHeight = 100,
                            scaleX = 2.0,
                            scaleY = 2.0,
                        ),
                        CaptureSurfaceScale(
                            pixelWidth = 800,
                            pixelHeight = 600,
                            scaleX = 1.0,
                            scaleY = 1.0,
                        ),
                    ),
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-2x2", key)
    }

    @Test
    fun `scale key matches a compose client surface when the decorated window does not`() {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val surfaces =
            captureSurfacesForBounds(
                awtWidth = 110,
                awtHeight = 70,
                insetLeft = 5,
                insetTop = 20,
                insetRight = 5,
                insetBottom = 0,
                scaleX = 2.0,
                scaleY = 2.0,
            ) + CaptureSurfaceScale(800, 600, 1.0, 1.0)
        val key =
            currentScaleKey(
                image = image,
                surfaces = surfaces,
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-2x2", key)
    }

    @Test
    fun `scale key matches an edge-rounded fractional-DPI client crop`() {
        // 150% density + inset offset: independent size rounding is 122×150, edge rounding
        // (same as screenRectToImageRect) is 121×150. Another density is visible so a miss
        // would fall back to the primary display.
        val image = BufferedImage(121, 150, BufferedImage.TYPE_INT_ARGB)
        val surfaces =
            captureSurfacesForBounds(
                awtWidth = 200,
                awtHeight = 100,
                insetLeft = 1,
                insetTop = 0,
                insetRight = 118,
                insetBottom = 0,
                scaleX = 1.5,
                scaleY = 1.5,
            ) + CaptureSurfaceScale(800, 600, 1.0, 1.0)
        val key =
            currentScaleKey(
                image = image,
                surfaces = surfaces,
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-1.5x1.5", key)
    }

    @Test
    fun `scale key matches a crop using the capture PNG effective scale`() {
        // 125% density + 801-DP capture: predicted PNG is 1001 px (round(801*1.25)),
        // so screenRectToImageRect uses 1001/801. A 202-DP panel is 252 px at that
        // ratio and 253 px at the nominal GraphicsConfiguration scale. Another
        // density is visible so a miss would fall back to the primary display.
        val image = BufferedImage(252, 100, BufferedImage.TYPE_INT_ARGB)
        val surfaces =
            captureSurfacesForBounds(
                awtWidth = 801,
                awtHeight = 80,
                insetLeft = 0,
                insetTop = 0,
                insetRight = 0,
                insetBottom = 0,
                scaleX = 1.25,
                scaleY = 1.25,
                extraAwtRegions = listOf(CaptureAwtRegion(0, 0, 202, 80)),
            ) + CaptureSurfaceScale(800, 600, 1.0, 1.0)
        val key =
            currentScaleKey(
                image = image,
                surfaces = surfaces,
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-1.25x1.25", key)
    }

    @Test
    fun `scale key matches an X11 crop using the client capture PNG effective scale`() {
        // Linux X11 native capture is the client area. An 811-DP window with a 10-DP
        // left inset is an 801-DP capture; the predicted PNG is 1001 px at 1.25×.
        // Using the outer window (811) would yield 1014 px and a 253-px panel crop.
        val image = BufferedImage(252, 100, BufferedImage.TYPE_INT_ARGB)
        val surfaces =
            captureSurfacesForBounds(
                awtWidth = 811,
                awtHeight = 80,
                insetLeft = 10,
                insetTop = 0,
                insetRight = 0,
                insetBottom = 0,
                scaleX = 1.25,
                scaleY = 1.25,
                extraAwtRegions = listOf(CaptureAwtRegion(10, 0, 202, 80)),
                captureOriginX = 10,
                captureOriginY = 0,
                captureAwtWidth = 801,
                captureAwtHeight = 80,
            ) + CaptureSurfaceScale(800, 600, 1.0, 1.0)
        val key =
            currentScaleKey(
                image = image,
                surfaces = surfaces,
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-1.25x1.25", key)
    }

    @Test
    fun `scale key matches an X11 client-origin panel crop at fractional DPI`() {
        // Linux X11 native capture starts at the client origin. A panel flush with that
        // origin is (1,0,81,100) in window space but (0,0,81,100) in capture space:
        // window-relative edge rounding is 121×150, client-relative is 122×150.
        val image = BufferedImage(122, 150, BufferedImage.TYPE_INT_ARGB)
        val surfaces =
            captureSurfacesForBounds(
                awtWidth = 200,
                awtHeight = 100,
                insetLeft = 1,
                insetTop = 0,
                insetRight = 118,
                insetBottom = 0,
                scaleX = 1.5,
                scaleY = 1.5,
                extraAwtRegions = listOf(CaptureAwtRegion(1, 0, 81, 100)),
                captureOriginX = 1,
                captureOriginY = 0,
                captureAwtWidth = 81,
                captureAwtHeight = 100,
            ) + CaptureSurfaceScale(800, 600, 1.0, 1.0)
        val key =
            currentScaleKey(
                image = image,
                surfaces = surfaces,
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-1.5x1.5", key)
    }

    @Test
    fun `scale key matches an embedded compose panel smaller than the content pane`() {
        val image = BufferedImage(160, 80, BufferedImage.TYPE_INT_ARGB)
        val surfaces =
            captureSurfacesForBounds(
                awtWidth = 220,
                awtHeight = 160,
                insetLeft = 5,
                insetTop = 20,
                insetRight = 5,
                insetBottom = 5,
                scaleX = 2.0,
                scaleY = 2.0,
                contentWidth = 210,
                contentHeight = 135,
                extraAwtSizes = listOf(80 to 40),
            ) + CaptureSurfaceScale(800, 600, 1.0, 1.0)
        val key =
            currentScaleKey(
                image = image,
                surfaces = surfaces,
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-2x2", key)
    }

    @Test
    fun `scale key uses a unique window density when the still size does not match`() {
        val image = BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)
        val key =
            currentScaleKey(
                image = image,
                surfaces = listOf(CaptureSurfaceScale(200, 100, 2.0, 2.0)),
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-2x2", key)
    }

    @Test
    fun `scale key falls back when matching surfaces disagree on density`() {
        val image = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)
        val key =
            currentScaleKey(
                image = image,
                surfaces =
                    listOf(
                        CaptureSurfaceScale(200, 100, 2.0, 2.0),
                        CaptureSurfaceScale(200, 100, 1.25, 1.25),
                    ),
                fallbackScaleX = 1.0,
                fallbackScaleY = 1.0,
            )
        assertEquals("scale-1x1", key)
    }

    @Test
    fun `hidden compose panels are not capture surfaces`() {
        val root = readAwtSnapshotOnEdt {
            val panel = ComposePanel()
            panel.setSize(80, 40)
            Container().also { it.add(panel) }
        }
        assertEquals(emptyList(), composePanelAwtRegions(root))
    }

    @Test
    fun `capture surface geometry is read on the EDT`() {
        val onEdt = AtomicBoolean(false)
        val value = readAwtSnapshotOnEdt {
            onEdt.set(SwingUtilities.isEventDispatchThread())
            "ok"
        }
        assertEquals("ok", value)
        assertTrue(onEdt.get())
    }

    @Test
    fun `explicit scale key is used instead of the inferred capture scale`(@TempDir temp: Path) {
        val goldRoot = temp.resolve("golds")
        val image = solid(2, 2, 0x00AA00)
        assertMatchesGold(
            name = "main-window",
            image = image,
            testClassName = "dev.example.HomeTest",
            testMethodName = "renders",
            goldRoot = goldRoot,
            reportsRoot = temp.resolve("reports"),
            osKey = "macos",
            scaleKey = "scale-2x2",
            updateEnabled = true,
        )
        val goldFile =
            ScreenshotGoldPaths.goldFile(
                goldRoot,
                "dev.example.HomeTest",
                "renders",
                "main-window",
                "macos",
                "scale-2x2",
            )
        assertTrue(Files.isRegularFile(goldFile))
    }

    private fun solid(width: Int, height: Int, rgb: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val packed = (0xFF shl 24) or (rgb and 0x00FFFFFF)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, packed)
            }
        }
        return image
    }
}

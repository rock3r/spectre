@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Color
import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CompositeScreenshotTest {
    @Test
    fun `composite uses union bounds including negative coordinates and paints back to front`() {
        val back =
            layer(Color.RED, width = 4, height = 3, bounds = Rectangle(-2, 1, 4, 3), order = 0)
        val front =
            layer(Color.BLUE, width = 2, height = 2, bounds = Rectangle(0, -1, 2, 2), order = 1)

        val composite = compositeWindowLayers(listOf(back, front))

        assertEquals(Rectangle(-2, -1, 4, 5), composite.boundsOnScreen)
        assertEquals(4, composite.image.width)
        assertEquals(5, composite.image.height)
        assertEquals(Color.BLUE.rgb, composite.image.getRGB(2, 1))
        assertEquals(Color.RED.rgb, composite.image.getRGB(0, 2))
    }

    @Test
    fun `composite preserves device resolution using maximum source density`() {
        val oneX =
            layer(Color.RED, width = 2, height = 2, bounds = Rectangle(0, 0, 2, 2), order = 0)
        val twoX =
            layer(Color.BLUE, width = 4, height = 4, bounds = Rectangle(2, 0, 2, 2), order = 1)

        val composite = compositeWindowLayers(listOf(oneX, twoX))

        assertEquals(2.0, composite.densityScale)
        assertEquals(8, composite.image.width)
        assertEquals(4, composite.image.height)
        assertEquals(Color.RED.rgb, composite.image.getRGB(1, 1))
        assertEquals(Color.BLUE.rgb, composite.image.getRGB(6, 1))
    }

    @Test
    fun `result omits composite when any requested window failed`() {
        val result =
            CompositeScreenshot(
                windows =
                    listOf(
                        WindowScreenshotResult.Failure(
                            windowIndex = 1,
                            title = "popup",
                            boundsOnScreen = Rectangle(5, 5, 10, 10),
                            captureOrder = 1,
                            message = "unsupported",
                        )
                    ),
                composite = null,
            )

        assertNull(result.composite)
    }

    @Test
    fun `capture retains successful sources and reports an invalid window extent`() {
        assumeLiveAwtAvailable()
        val main = Frame("main").apply { addNotify() }
        val popup = Frame("popup").apply { addNotify() }
        val targets =
            listOf(
                target(0, main, Rectangle(-10, 5, 20, 10)),
                target(1, popup, Rectangle(5, 0, 0, 10)),
            )
        val backend =
            object : ScreenCaptureBackend {
                override fun captureRegion(region: Rectangle?): BufferedImage = error("unused")

                override fun captureStillRegion(region: Rectangle?): BufferedImage = error("unused")

                override fun captureWindow(
                    window: TrackedWindow,
                    windowBounds: Rectangle,
                    frameInsets: Insets,
                ): WindowCapture =
                    WindowCapture(
                        image = BufferedImage(20, 10, BufferedImage.TYPE_INT_ARGB),
                        boundsOnScreen = windowBounds,
                    )
            }
        try {
            val result = captureCompositeWindows(targets, backend)

            assertIs<WindowScreenshotResult.Success>(result.windows[0])
            val failure = assertIs<WindowScreenshotResult.Failure>(result.windows[1])
            assertEquals(1, failure.windowIndex)
            assertEquals(1, failure.captureOrder)
            assertNull(result.composite)
        } finally {
            popup.dispose()
            main.dispose()
        }
    }

    private fun layer(
        color: Color,
        width: Int,
        height: Int,
        bounds: Rectangle,
        order: Int,
    ): WindowScreenshotResult.Success =
        WindowScreenshotResult.Success(
            windowIndex = order,
            surfaceId = "surface-$order",
            title = "window-$order",
            boundsOnScreen = bounds,
            densityScaleX = width.toDouble() / bounds.width,
            densityScaleY = height.toDouble() / bounds.height,
            captureOrder = order,
            image =
                BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
                    createGraphics().use { graphics ->
                        graphics.color = color
                        graphics.fillRect(0, 0, width, height)
                    }
                },
        )

    private fun target(index: Int, frame: Frame, bounds: Rectangle): CompositeWindowTarget =
        CompositeWindowTarget(
            windowIndex = index,
            trackedWindow = TrackedWindow("surface-$index", frame, null, index > 0),
            windowBounds = bounds,
            windowInsets = Insets(0, 0, 0, 0),
        )
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

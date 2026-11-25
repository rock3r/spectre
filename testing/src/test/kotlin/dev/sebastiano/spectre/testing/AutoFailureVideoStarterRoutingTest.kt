package dev.sebastiano.spectre.testing

import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.Rectangle
import java.awt.Transparency
import java.awt.geom.AffineTransform
import java.awt.image.ColorModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoFailureVideoStarterRoutingTest {
    @Test
    fun `X11 duplicate AWT titles fail before native title routing`() {
        val requested = Frame("shared")
        val duplicate = Dialog(requested, "shared")
        try {
            requested.addNotify()
            duplicate.addNotify()

            val error =
                assertFailsWith<IllegalStateException> {
                    routeX11SafeWindow(
                        window = requested,
                        windows = listOf(requested, duplicate),
                        isLinuxX11 = true,
                    ) {
                        error("ambiguous window must not reach the recorder route")
                    }
                }

            assertTrue(error.message.orEmpty().contains("requested AWT window identity"))
        } finally {
            duplicate.dispose()
            requested.dispose()
        }
    }

    @Test
    fun `X11 unique AWT title passes the real window guard`() {
        val requested = Frame("unique")
        val other = Frame("different")
        try {
            requested.addNotify()
            other.addNotify()

            val routed =
                routeX11SafeWindow(
                    window = requested,
                    windows = listOf(requested, other),
                    isLinuxX11 = true,
                ) { window ->
                    window
                }

            assertEquals("unique", routed.title)
            assertEquals(requested.bounds, routed.bounds)
        } finally {
            other.dispose()
            requested.dispose()
        }
    }

    @Test
    fun `macOS display mapping follows helper ordering for negative and stacked Retina displays`() {
        val primary = FakeDevice("primary", Rectangle(0, 0, 1512, 982), scale = 2.0)
        val left = FakeDevice("left", Rectangle(-1920, 0, 1920, 1080), scale = 1.0)
        val above = FakeDevice("above", Rectangle(0, -1440, 2560, 1440), scale = 2.0)
        val devices = listOf(above, left, primary)

        assertEquals(0, macOsBackendDisplayIndex(primary, devices, primary, isMacOs = true))
        assertEquals(1, macOsBackendDisplayIndex(left, devices, primary, isMacOs = true))
        assertEquals(2, macOsBackendDisplayIndex(above, devices, primary, isMacOs = true))
        assertEquals(
            DisplayRecordingRequest(Rectangle(0, 0, 2560, 1440), backendIndex = 2),
            displayRecordingRequest(above, devices, primary, isMacOs = true),
        )
        assertEquals(
            DisplayRecordingRequest(Rectangle(-1920, 0, 1920, 1080), backendIndex = 0),
            displayRecordingRequest(left, devices, primary, isMacOs = false),
        )
    }

    @Test
    fun `macOS display mapping fails when AWT geometry cannot identify one backend display`() {
        val primary = FakeDevice("primary", Rectangle(0, 0, 1512, 982), scale = 2.0)
        val duplicate = FakeDevice("duplicate", Rectangle(0, 0, 1512, 982), scale = 1.0)

        val error =
            assertFailsWith<IllegalStateException> {
                macOsBackendDisplayIndex(
                    duplicate,
                    listOf(primary, duplicate),
                    primary,
                    isMacOs = true,
                )
            }

        assertTrue(error.message.orEmpty().contains("ambiguous"))
    }
}

private class FakeDevice(
    private val id: String,
    bounds: Rectangle,
    scale: Double,
) : GraphicsDevice() {
    private val configuration = FakeConfiguration(this, bounds, scale)

    override fun getType(): Int = TYPE_RASTER_SCREEN

    override fun getIDstring(): String = id

    override fun getConfigurations(): Array<GraphicsConfiguration> = arrayOf(configuration)

    override fun getDefaultConfiguration(): GraphicsConfiguration = configuration
}

private class FakeConfiguration(
    private val owner: GraphicsDevice,
    private val displayBounds: Rectangle,
    scale: Double,
) : GraphicsConfiguration() {
    private val transform = AffineTransform.getScaleInstance(scale, scale)

    override fun getDevice(): GraphicsDevice = owner

    override fun getColorModel(): ColorModel = ColorModel.getRGBdefault()

    override fun getColorModel(transparency: Int): ColorModel? =
        if (transparency == Transparency.OPAQUE) ColorModel.getRGBdefault() else null

    override fun getDefaultTransform(): AffineTransform = AffineTransform(transform)

    override fun getNormalizingTransform(): AffineTransform = AffineTransform()

    override fun getBounds(): Rectangle = Rectangle(displayBounds)
}

package dev.sebastiano.spectre.cli.daemon

import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

class DaemonSessionRecordingFullscreenTest {
    @Test
    fun `fullscreen region bounds use the primary display when only one screen is present`() {
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "display bounds need a non-headless GraphicsEnvironment",
        )
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        assumeFalse(
            env.screenDevices.size != 1,
            "this machine has ${env.screenDevices.size} screens",
        )
        val bounds = requireFullscreenRegionBounds(env)
        assertTrue(bounds.width > 0, "width=${bounds.width}")
        assertTrue(bounds.height > 0, "height=${bounds.height}")
        assertEquals(env.defaultScreenDevice.defaultConfiguration.bounds, bounds)
    }

    @Test
    fun `fullscreen region bounds reject multi-monitor desktops`() {
        val fake =
            object : GraphicsEnvironment() {
                override fun getScreenDevices(): Array<GraphicsDevice> =
                    arrayOf(fakeDevice(), fakeDevice())

                override fun getDefaultScreenDevice(): GraphicsDevice = fakeDevice()

                override fun createGraphics(img: java.awt.image.BufferedImage?) =
                    throw UnsupportedOperationException()

                override fun getAllFonts(): Array<java.awt.Font> = emptyArray()

                override fun getAvailableFontFamilyNames(): Array<String> = emptyArray()

                override fun getAvailableFontFamilyNames(l: java.util.Locale?): Array<String> =
                    emptyArray()
            }
        val error = assertFailsWith<IOException> { requireFullscreenRegionBounds(fake) }
        assertTrue(error.message!!.contains("multi-monitor", ignoreCase = true), error.message)
        assertTrue(error.message!!.contains("single display", ignoreCase = true), error.message)
    }

    private fun fakeDevice(): GraphicsDevice =
        object : GraphicsDevice() {
            override fun getType(): Int = TYPE_RASTER_SCREEN

            override fun getIDstring(): String = "fake"

            override fun getConfigurations(): Array<java.awt.GraphicsConfiguration> =
                arrayOf(fakeConfig())

            override fun getDefaultConfiguration(): java.awt.GraphicsConfiguration = fakeConfig()
        }

    private fun fakeConfig(): java.awt.GraphicsConfiguration =
        object : java.awt.GraphicsConfiguration() {
            override fun getDevice(): GraphicsDevice = fakeDevice()

            override fun getColorModel(): java.awt.image.ColorModel =
                java.awt.image.ColorModel.getRGBdefault()

            override fun getColorModel(transparency: Int): java.awt.image.ColorModel =
                java.awt.image.ColorModel.getRGBdefault()

            override fun getDefaultTransform(): java.awt.geom.AffineTransform =
                java.awt.geom.AffineTransform()

            override fun getNormalizingTransform(): java.awt.geom.AffineTransform =
                java.awt.geom.AffineTransform()

            override fun getBounds(): Rectangle = Rectangle(0, 0, 100, 100)
        }
}

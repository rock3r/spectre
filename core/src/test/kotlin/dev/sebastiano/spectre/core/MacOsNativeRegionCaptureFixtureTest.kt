@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Color
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Opt-in, capture-only fixture against the real bundled macOS helper and current desktop. */
class MacOsNativeRegionCaptureFixtureTest {
    @Test
    fun `normal RobotDriver captures logical device fullscreen and region after still session`() {
        assumeTrue(detectMacOs(), "ScreenCaptureKit is macOS-only")
        assumeTrue(
            System.getProperty("spectre.test.nativeMacCapture").toBoolean(),
            "Run with -Dspectre.test.nativeMacCapture=true on the read-only macOS fixture",
        )
        val frame =
            Frame("Spectre native region fixture").apply {
                background = Color(0x23, 0x71, 0x4D)
                setBounds(80, 80, 240, 160)
                isVisible = true
            }
        try {
            val driver = RobotDriver()
            val backend = PlatformScreenCaptureBackend(driver)
            val tracked =
                TrackedWindow("native-fixture", frame, composePanel = null, isPopup = false)
            val window = backend.captureWindow(tracked, Rectangle(frame.bounds))
            assertTrue(window.image.width > 0 && window.image.height > 0)

            // Region capture must stop the open window-stills SCK session before starting.
            val region = driver.screenshot(Rectangle(frame.bounds))
            assertEquals(frame.width, region.width)
            assertEquals(frame.height, region.height)
            assertTrue(region.getRGB(region.width / 2, region.height / 2) ushr 24 != 0)

            val desktop = virtualDesktopBounds()
            val logical = driver.screenshot()
            val device = driver.screenshotAtDeviceScale()
            assertEquals(desktop.width, logical.width)
            assertEquals(desktop.height, logical.height)
            val transform =
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    .defaultConfiguration
                    .defaultTransform
            assertEquals((desktop.width * transform.scaleX).toInt(), device.width)
            assertEquals((desktop.height * transform.scaleY).toInt(), device.height)
            assertTrue(device.getRGB(device.width / 2, device.height / 2) ushr 24 != 0)
        } finally {
            frame.dispose()
        }
    }
}

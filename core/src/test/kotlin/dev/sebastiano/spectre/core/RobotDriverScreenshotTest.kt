@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotDriverScreenshotTest {
    @Test
    fun `screenshot consults the screen-recording guard before capturing`() {
        val guard = RecordingTccGuard()
        val robot = RecordingRobotAdapter()
        val capture = RecordingScreenCaptureAdapter()
        val driver = RobotDriver(robot, RecordingClipboardAdapter(), guard, capture)

        driver.screenshot(Rectangle(0, 0, 4, 4))

        assertEquals(0, guard.accessibilityCalls)
        assertEquals(1, guard.screenRecordingCalls)
        assertEquals(1, guard.captureValidationCalls)
        assertEquals(1, capture.captureCalls)
        assertEquals(0, robot.captureCalls)
    }

    @Test
    fun `screenshot that fails the screen-recording check does not capture`() {
        val guard = RecordingTccGuard(screenRecordingThrows = true)
        val capture = RecordingScreenCaptureAdapter()
        val driver =
            RobotDriver(RecordingRobotAdapter(), RecordingClipboardAdapter(), guard, capture)

        assertFailsWith<IllegalStateException> { driver.screenshot(Rectangle(0, 0, 4, 4)) }

        assertEquals(0, capture.captureCalls)
    }

    @Test
    fun `device-scale screenshot consults guard and validates captured pixels`() {
        val guard = RecordingTccGuard()
        val capture = RecordingScreenCaptureAdapter()
        val driver =
            RobotDriver(RecordingRobotAdapter(), RecordingClipboardAdapter(), guard, capture)

        driver.screenshotAtDeviceScale(Rectangle(0, 0, 4, 4))

        assertEquals(1, guard.screenRecordingCalls)
        assertEquals(1, guard.captureValidationCalls)
        assertEquals(1, capture.captureCalls)
    }

    @Test
    fun `device-scale screenshot blocked by guard does not capture`() {
        val guard = RecordingTccGuard(screenRecordingThrows = true)
        val capture = RecordingScreenCaptureAdapter()
        val driver =
            RobotDriver(RecordingRobotAdapter(), RecordingClipboardAdapter(), guard, capture)

        assertFailsWith<IllegalStateException> {
            driver.screenshotAtDeviceScale(Rectangle(0, 0, 4, 4))
        }

        assertEquals(0, capture.captureCalls)
    }

    @Test
    fun `injected screenshot adapter does not route through macOS native capture`() {
        val capture = RecordingScreenCaptureAdapter()
        var nativeCalls = 0
        val driver =
            RobotDriver(
                robot = RecordingRobotAdapter(),
                clipboard = RecordingClipboardAdapter(),
                tccGuard = RecordingTccGuard(),
                screenCapture = capture,
                nativeRegionCapture = {
                    nativeCalls++
                    BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
                },
                isMacOs = { true },
            )

        driver.screenshot(Rectangle(0, 0, 4, 4))

        assertEquals(1, capture.captureCalls)
        assertEquals(0, nativeCalls)
        assertFalse(driver.allowsPlatformCapture)
    }

    @Test
    fun `macOS screenshot routes through native region capture without Robot or TCC probe`() {
        val robot = RecordingRobotAdapter(platformCapture = true)
        val guard = RecordingTccGuard()
        val requested = Rectangle(11, 22, 30, 20)
        val native = BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB)
        val driver =
            RobotDriver(
                robot = robot,
                clipboard = RecordingClipboardAdapter(),
                tccGuard = guard,
                nativeRegionCapture = { region ->
                    assertEquals(requested, region)
                    native
                },
                isMacOs = { true },
            )

        val image = driver.screenshot(requested)

        assertEquals(30, image.width)
        assertEquals(20, image.height)
        assertEquals(0, guard.screenRecordingCalls)
        assertEquals(0, robot.captureCalls)
    }

    @Test
    fun `macOS device-scale screenshot returns native helper pixels unchanged`() {
        val requested = Rectangle(11, 22, 30, 20)
        val native = BufferedImage(60, 40, BufferedImage.TYPE_INT_ARGB)
        val driver =
            RobotDriver(
                robot = RecordingRobotAdapter(platformCapture = true),
                clipboard = RecordingClipboardAdapter(),
                nativeRegionCapture = { native },
                isMacOs = { true },
            )

        assertTrue(driver.screenshotAtDeviceScale(requested) === native)
    }

    @Test
    fun `non-macOS screenshot keeps Robot capture path`() {
        val robot = RecordingRobotAdapter()
        var nativeCalls = 0
        val driver =
            RobotDriver(
                robot = robot,
                clipboard = RecordingClipboardAdapter(),
                nativeRegionCapture = {
                    nativeCalls++
                    BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                },
                isMacOs = { false },
            )

        driver.screenshot(Rectangle(1, 2, 3, 4))

        assertEquals(0, nativeCalls)
    }

    @Test
    fun `headless screenshot throws UnsupportedOperationException`() {
        val driver = RobotDriver.headless()
        assertFailsWith<UnsupportedOperationException> { driver.screenshot() }
    }
}

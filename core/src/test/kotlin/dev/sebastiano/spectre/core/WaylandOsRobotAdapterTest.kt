@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WaylandOsRobotAdapterTest {
    @Test
    fun `X11 and non-Linux keep the AWT robot adapter`() {
        val awt = FakeAwtAdapter()
        val adapter =
            defaultRealOsRobotAdapter(
                isLinux = true,
                wayland = false,
                waylandAdapter = { error("wayland adapter must not be loaded on X11") },
                awtAdapter = { awt },
            )
        assertSame(awt, adapter)
    }

    @Test
    fun `macOS and Windows keep the AWT robot adapter even if a Wayland env leaked`() {
        val awt = FakeAwtAdapter()
        val adapter =
            defaultRealOsRobotAdapter(
                isLinux = false,
                wayland = true,
                waylandAdapter = { error("must not load Wayland adapter off Linux") },
                awtAdapter = { awt },
            )
        assertSame(awt, adapter)
    }

    @Test
    fun `Linux Wayland uses the helper-backed adapter`() {
        val wayland = RecordingWaylandAdapter()
        val adapter =
            defaultRealOsRobotAdapter(
                isLinux = true,
                wayland = true,
                waylandAdapter = { wayland },
                awtAdapter = { error("AWT Robot must not open a portal on Wayland") },
            )
        assertSame(wayland, adapter)
        adapter.mouseMove(12, 24)
        adapter.mousePress(1024)
        adapter.mouseRelease(1024)
        adapter.keyPress(10)
        adapter.keyRelease(10)
        assertEquals(
            listOf("move:12,24", "press:1024", "release:1024", "keyPress:10", "keyRelease:10"),
            wayland.calls,
        )
    }

    @Test
    fun `Linux Wayland fails closed on first use when the helper bridge is missing`() {
        val adapter =
            defaultRealOsRobotAdapter(
                isLinux = true,
                wayland = true,
                waylandAdapter = { null },
                awtAdapter = { error("must not fall back to AWT Robot") },
            )
        val error = assertFailsWith<IllegalStateException> { adapter.mouseMove(0, 0) }
        assertTrue(error.message.orEmpty().contains("spectre-wayland-helper"))
        assertTrue(error.message.orEmpty().contains("java.awt.Robot"))
    }

    @Test
    fun `Wayland helper adapter counts as real OS input for leases`() {
        val driver = RobotDriver(robot = RecordingWaylandAdapter())
        assertTrue(driver.inputCapabilities.realOsInput)
    }

    private class FakeAwtAdapter : RobotAdapter {
        override val autoDelayMs: Int = 0
        override val requiresOffEdt: Boolean = true

        override fun mouseMove(x: Int, y: Int) = Unit

        override fun mousePress(buttons: Int) = Unit

        override fun mouseRelease(buttons: Int) = Unit

        override fun keyPress(keyCode: Int) = Unit

        override fun keyRelease(keyCode: Int) = Unit

        override fun mouseWheel(wheelClicks: Int) = Unit

        override fun waitForIdle() = Unit

        override fun createScreenCapture(region: Rectangle): BufferedImage =
            BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    }

    private class RecordingWaylandAdapter : RobotAdapter {
        val calls = mutableListOf<String>()
        override val autoDelayMs: Int = 0
        override val requiresOffEdt: Boolean = true
        override val deliversRealOsInput: Boolean = true

        override fun mouseMove(x: Int, y: Int) {
            calls += "move:$x,$y"
        }

        override fun mousePress(buttons: Int) {
            calls += "press:$buttons"
        }

        override fun mouseRelease(buttons: Int) {
            calls += "release:$buttons"
        }

        override fun keyPress(keyCode: Int) {
            calls += "keyPress:$keyCode"
        }

        override fun keyRelease(keyCode: Int) {
            calls += "keyRelease:$keyCode"
        }

        override fun mouseWheel(wheelClicks: Int) {
            calls += "wheel:$wheelClicks"
        }

        override fun waitForIdle() = Unit

        override fun createScreenCapture(region: Rectangle): BufferedImage =
            BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    }
}

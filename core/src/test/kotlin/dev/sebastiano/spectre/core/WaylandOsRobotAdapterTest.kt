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
    fun `inject without recording still uses a live seat socket`() {
        val seat = RecordingWaylandAdapter()
        val adapter = resolveWaylandRobotAdapter(recordingBridge = null, liveSeat = seat)
        assertSame(seat, adapter)
    }

    @Test
    fun `recording bridge wins over a live seat socket`() {
        val recording = RecordingWaylandAdapter()
        val seat = RecordingWaylandAdapter()
        assertSame(recording, resolveWaylandRobotAdapter(recording, seat))
    }

    @Test
    fun `Wayland adapters drain the clipboard after paste`() {
        assertTrue(MissingWaylandHelperAdapter.shouldDrainAfterClipboardPaste)
        assertTrue(seatSocketRobotAdapterForTests().shouldDrainAfterClipboardPaste)
    }

    @Test
    fun `Wayland adapters do not advertise an unapplied auto-delay`() {
        assertEquals(0, MissingWaylandHelperAdapter.autoDelayMs)
        assertEquals(0, seatSocketRobotAdapterForTests().autoDelayMs)
    }

    @Test
    fun `seat screenshot commands include AWT display bounds for HiDPI mapping`() {
        val json =
            waylandScreenshotCommandJson(
                region = Rectangle(1362, 10, 480, 240),
                outputPath = "/tmp/a.png",
                screenSize = Rectangle(0, 0, 2560, 1440),
            )
        assertTrue(json.contains("\"screen_size\":[0,0,2560,1440]"))
        assertTrue(json.contains("\"region\":{\"x\":1362"))
    }

    @Test
    fun `logical screenshots are resized back to the requested AWT region`() {
        val device = BufferedImage(288, 144, BufferedImage.TYPE_INT_ARGB)
        val logical = screenshotToLogicalSize(device, Rectangle(10, 20, 480, 240))
        assertEquals(480, logical.width)
        assertEquals(240, logical.height)
        val alreadyLogical = screenshotToLogicalSize(device, Rectangle(0, 0, 288, 144))
        assertSame(device, alreadyLogical)
    }

    @Test
    fun `SPECTRE_WAYLAND_HELPER is required to spawn a helper from inject`() {
        assertEquals(null, startHelperFromEnv { null })
    }

    @Test
    fun `injected helper spawn fails closed when the child exits before binding`() {
        val sleeps = mutableListOf<Long>()
        val error =
            assertFailsWith<IllegalStateException> {
                awaitInjectedHelperSocket(
                    liveSocket = { null },
                    helperAlive = { false },
                    exitDetail = { "exit 1" },
                    timeoutMs = 90_000,
                    pollMs = 50,
                    sleep = { sleeps += it },
                )
            }
        assertTrue(error.message.orEmpty().contains("exit 1"))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `injected helper spawn joins a socket that appears after the child exits`() {
        val socket = java.nio.file.Path.of("/tmp/spectre-seat/wayland-session.sock")
        val resolved =
            awaitInjectedHelperSocket(
                liveSocket = { socket },
                helperAlive = { false },
                exitDetail = { "exit 1" },
                timeoutMs = 90_000,
                pollMs = 50,
                sleep = { error("must not wait after a live socket") },
            )
        assertEquals(socket, resolved)
    }

    @Test
    fun `seat socket path is Spectre-owned not java robot`() {
        val path =
            requireNotNull(
                waylandSessionSocketFromEnv { key ->
                    when (key) {
                        "SPECTRE_WAYLAND_SESSION_DIR" -> "/tmp/spectre-seat"
                        else -> null
                    }
                }
            )
        assertEquals("/tmp/spectre-seat/wayland-session.sock", path.toString())
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

    private fun seatSocketRobotAdapterForTests(): RobotAdapter =
        WaylandSeatSocketAdapter(socketPath = { error("tests must not open a socket") })
}

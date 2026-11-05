package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FramedWindowTest {

    @Test
    fun `Windows device frame scales each edge from the monitor origin`() {
        // A 1.5x monitor to the right of a 1920px primary: AWT keeps the monitor origin in device
        // pixels and scales only the offset inside the monitor.
        val device =
            deviceFrameFor(
                logical = Rectangle(2000, 100, 300, 200),
                scaleX = 1.5,
                scaleY = 1.5,
                screenOrigin = Point(1920, 0),
                scalesFromScreenOrigin = true,
            )

        assertEquals(Rectangle(2040, 150, 450, 300), device)
    }

    @Test
    fun `X11 device frame scales absolute coordinates`() {
        val device =
            deviceFrameFor(
                logical = Rectangle(100, 50, 300, 200),
                scaleX = 2.0,
                scaleY = 2.0,
                screenOrigin = Point(960, 0),
                scalesFromScreenOrigin = false,
            )

        assertEquals(Rectangle(200, 100, 600, 400), device)
    }

    @Test
    fun `unscaled device frame equals the logical frame`() {
        val logical = Rectangle(-1280, 40, 300, 200)

        val device =
            deviceFrameFor(
                logical = logical,
                scaleX = 1.0,
                scaleY = 1.0,
                screenOrigin = Point(-1280, 0),
                scalesFromScreenOrigin = true,
            )

        assertEquals(logical, device)
    }

    @Test
    fun `framed window has no title and never asks for a discriminator`() {
        val window =
            FramedWindow(
                logicalFrame = Rectangle(10, 20, 300, 200),
                deviceFrame = Rectangle(20, 40, 600, 400),
            )

        assertNull(window.title)
        assertFalse(window.requiresTitleDiscriminator)
        assertEquals(Rectangle(10, 20, 300, 200), window.bounds)
        assertFailsWith<UnsupportedOperationException> { window.title = "renamed" }
    }
}

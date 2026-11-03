@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class HiDpiMapperTest {

    @Test
    fun `converts compose X to AWT screen X at 1x scale`() {
        val result = composeToAwtX(composeX = 100f, scaleX = 1f, panelScreenX = 50)
        assertEquals(150, result)
    }

    @Test
    fun `converts compose Y to AWT screen Y at 1x scale`() {
        val result = composeToAwtY(composeY = 200f, scaleY = 1f, panelScreenY = 30)
        assertEquals(230, result)
    }

    @Test
    fun `divides compose X by scale at 2x HiDPI`() {
        val result = composeToAwtX(composeX = 200f, scaleX = 2f, panelScreenX = 50)
        assertEquals(150, result)
    }

    @Test
    fun `divides compose Y by scale at 2x HiDPI`() {
        val result = composeToAwtY(composeY = 400f, scaleY = 2f, panelScreenY = 30)
        assertEquals(230, result)
    }

    @Test
    fun `handles fractional scale for X`() {
        val result = composeToAwtX(composeX = 150f, scaleX = 1.5f, panelScreenX = 10)
        assertEquals(110, result)
    }

    @Test
    fun `handles fractional scale for Y`() {
        val result = composeToAwtY(composeY = 300f, scaleY = 1.5f, panelScreenY = 20)
        assertEquals(220, result)
    }

    @Test
    fun `computes center of compose bounds in AWT screen coordinates at 2x`() {
        val result =
            composeBoundsToAwtCenter(
                left = 100f,
                top = 200f,
                right = 300f,
                bottom = 400f,
                scaleX = 2f,
                scaleY = 2f,
                panelScreenX = 10,
                panelScreenY = 20,
            )
        assertEquals(Point(110, 170), result)
    }

    @Test
    fun `computes center of compose bounds in AWT screen coordinates at 1x`() {
        val result =
            composeBoundsToAwtCenter(
                left = 0f,
                top = 0f,
                right = 100f,
                bottom = 80f,
                scaleX = 1f,
                scaleY = 1f,
                panelScreenX = 50,
                panelScreenY = 50,
            )
        assertEquals(Point(100, 90), result)
    }

    @Test
    fun `computes center with asymmetric scale factors`() {
        // scaleX=2, scaleY=1 — X coordinates halved, Y unchanged
        val result =
            composeBoundsToAwtCenter(
                left = 100f,
                top = 100f,
                right = 300f,
                bottom = 200f,
                scaleX = 2f,
                scaleY = 1f,
                panelScreenX = 10,
                panelScreenY = 10,
            )
        // AWT X: left=60, right=160 → center=110
        // AWT Y: top=110, bottom=210 → center=160
        assertEquals(Point(110, 160), result)
    }

    @Test
    fun `computes capture rectangle in AWT screen coordinates`() {
        val result =
            composeBoundsToAwtRectangle(
                left = 100f,
                top = 200f,
                right = 300f,
                bottom = 500f,
                scaleX = 2f,
                scaleY = 2f,
                panelScreenX = 10,
                panelScreenY = 20,
            )

        assertEquals(Rectangle(60, 120, 100, 150), result)
    }

    @Test
    fun `capture rectangle is at least one pixel in each dimension`() {
        val result =
            composeBoundsToAwtRectangle(
                left = 10f,
                top = 20f,
                right = 10.4f,
                bottom = 20.4f,
                scaleX = 1f,
                scaleY = 1f,
                panelScreenX = 0,
                panelScreenY = 0,
            )

        assertEquals(Rectangle(10, 20, 1, 1), result)
    }

    @Test
    fun `capture rectangle rounds outward to preserve trailing edge pixels`() {
        val result =
            composeBoundsToAwtRectangle(
                left = 10.2f,
                top = 5.1f,
                right = 20.8f,
                bottom = 9.9f,
                scaleX = 1f,
                scaleY = 1f,
                panelScreenX = 0,
                panelScreenY = 0,
            )

        assertEquals(Rectangle(10, 5, 11, 5), result)
    }

    @Test
    fun `window-relative rectangle subtracts the window origin and keeps title-bar chrome`() {
        // 2x display; window at (300, 200) on screen; Compose content 28 logical px below the
        // window's top edge (title bar). A 24x16 logical-pixel button at Compose (40, 60) physical.
        val result =
            composeBoundsToAwtWindowRectangle(
                left = 40f,
                top = 60f,
                right = 88f,
                bottom = 92f,
                scaleX = 2f,
                scaleY = 2f,
                contentOriginOnScreen = Point(300, 228),
                windowOriginOnScreen = Point(300, 200),
            )

        assertEquals(Rectangle(20, 58, 24, 16), result)
    }

    @Test
    fun `window-relative rectangle matches the on-screen rectangle shifted by the window origin`() {
        val contentOrigin = Point(1012, 431)
        val windowOrigin = Point(1000, 400)
        val onScreen =
            composeBoundsToAwtRectangle(
                left = 13.3f,
                top = 7.7f,
                right = 141.9f,
                bottom = 55.1f,
                scaleX = 1.25f,
                scaleY = 1.25f,
                panelScreenX = contentOrigin.x,
                panelScreenY = contentOrigin.y,
            )

        val inWindow =
            composeBoundsToAwtWindowRectangle(
                left = 13.3f,
                top = 7.7f,
                right = 141.9f,
                bottom = 55.1f,
                scaleX = 1.25f,
                scaleY = 1.25f,
                contentOriginOnScreen = contentOrigin,
                windowOriginOnScreen = windowOrigin,
            )

        onScreen.translate(-windowOrigin.x, -windowOrigin.y)
        assertEquals(onScreen, inWindow)
    }

    @Test
    fun `window-relative rectangle is independent of a negative virtual-desktop origin`() {
        // Secondary monitor left of the primary at 150%: the window sits at negative screen x.
        val result =
            composeBoundsToAwtWindowRectangle(
                left = 30f,
                top = 45f,
                right = 180f,
                bottom = 90f,
                scaleX = 1.5f,
                scaleY = 1.5f,
                contentOriginOnScreen = Point(-1912, 132),
                windowOriginOnScreen = Point(-1920, 100),
            )

        assertEquals(Rectangle(28, 62, 100, 30), result)
    }
}

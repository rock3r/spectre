package dev.sebastiano.spectre.core

import java.awt.Point
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
}

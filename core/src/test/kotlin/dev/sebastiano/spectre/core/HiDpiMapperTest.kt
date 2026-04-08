package dev.sebastiano.spectre.core

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class HiDpiMapperTest {

    @Test
    fun `converts compose X to AWT screen X at 1x scale`() {
        val result = composeToAwtX(composeX = 100f, scale = 1f, panelScreenX = 50)
        assertEquals(150, result)
    }

    @Test
    fun `converts compose Y to AWT screen Y at 1x scale`() {
        val result = composeToAwtY(composeY = 200f, scale = 1f, panelScreenY = 30)
        assertEquals(230, result)
    }

    @Test
    fun `divides compose X by scale at 2x HiDPI`() {
        val result = composeToAwtX(composeX = 200f, scale = 2f, panelScreenX = 50)
        assertEquals(150, result)
    }

    @Test
    fun `divides compose Y by scale at 2x HiDPI`() {
        val result = composeToAwtY(composeY = 400f, scale = 2f, panelScreenY = 30)
        assertEquals(230, result)
    }

    @Test
    fun `handles fractional scale for X`() {
        val result = composeToAwtX(composeX = 150f, scale = 1.5f, panelScreenX = 10)
        assertEquals(110, result)
    }

    @Test
    fun `handles fractional scale for Y`() {
        val result = composeToAwtY(composeY = 300f, scale = 1.5f, panelScreenY = 20)
        assertEquals(220, result)
    }

    @Test
    fun `converts compose size by dividing by scale`() {
        val (width, height) =
            composeToAwtSize(composeWidth = 200f, composeHeight = 400f, scale = 2f)
        assertEquals(100, width)
        assertEquals(200, height)
    }

    @Test
    fun `computes center of compose bounds in AWT screen coordinates at 2x`() {
        // Compose bounds: left=100, top=200, right=300, bottom=400
        // Scale 2x, panel at (10, 20)
        // AWT left = 100/2 + 10 = 60, AWT top = 200/2 + 20 = 120
        // AWT right = 300/2 + 10 = 160, AWT bottom = 400/2 + 20 = 220
        // Center = (110, 170)
        val result =
            composeBoundsToAwtCenter(
                left = 100f,
                top = 200f,
                right = 300f,
                bottom = 400f,
                scale = 2f,
                panelScreenX = 10,
                panelScreenY = 20,
            )
        assertEquals(Point(110, 170), result)
    }

    @Test
    fun `computes center of compose bounds in AWT screen coordinates at 1x`() {
        // Compose bounds: left=0, top=0, right=100, bottom=80
        // Scale 1x, panel at (50, 50)
        // AWT left = 0 + 50 = 50, AWT top = 0 + 50 = 50
        // AWT right = 100 + 50 = 150, AWT bottom = 80 + 50 = 130
        // Center = (100, 90)
        val result =
            composeBoundsToAwtCenter(
                left = 0f,
                top = 0f,
                right = 100f,
                bottom = 80f,
                scale = 1f,
                panelScreenX = 50,
                panelScreenY = 50,
            )
        assertEquals(Point(100, 90), result)
    }
}

package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TrackedWindowTest {

    @Test
    fun `TrackedWindow stores surfaceId and popup flag`() {
        val window = javax.swing.JFrame("Test")
        val tracked =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        assertEquals("main:0", tracked.surfaceId)
        assertEquals(false, tracked.isPopup)
    }

    @Test
    fun `TrackedWindows with same surfaceId and window are equal`() {
        val window = javax.swing.JFrame("Test")
        val a =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        val b =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        assertEquals(a, b)
    }

    @Test
    fun `TrackedWindows with different surfaceId are not equal`() {
        val window = javax.swing.JFrame("Test")
        val a =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        val b =
            TrackedWindow(
                surfaceId = "popup:1",
                window = window,
                composePanel = null,
                isPopup = true,
            )
        assertNotEquals(a, b)
    }
}

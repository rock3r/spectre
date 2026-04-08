package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.condition.EnabledIf

@EnabledIf("isNotHeadless")
class TrackedWindowTest {

    @Test
    fun `TrackedWindow stores surfaceId and popup flag`() {
        val window = JFrame("Test")
        val tracked =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        assertEquals("main:0", tracked.surfaceId)
        assertEquals(false, tracked.isPopup)
        window.dispose()
    }

    @Test
    fun `TrackedWindows with same surfaceId and window are equal`() {
        val window = JFrame("Test")
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
        window.dispose()
    }

    @Test
    fun `TrackedWindows with different surfaceId are not equal`() {
        val window = JFrame("Test")
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
        window.dispose()
    }

    companion object {

        @JvmStatic fun isNotHeadless(): Boolean = !GraphicsEnvironment.isHeadless()
    }
}

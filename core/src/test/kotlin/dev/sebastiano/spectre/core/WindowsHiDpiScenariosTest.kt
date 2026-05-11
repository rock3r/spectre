@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [HiDpiMapper] against scenarios captured by [WindowsHiDpiDiagnostic] on a real Windows 11
 * box with mixed-DPI monitors (Display0 at 200%, x=-3840; Display1 at user-toggled 100/125/150%,
 * x=0). Each test uses the *exact* `boundsInWindow` / `panelScreenX` / `panelScreenY` values
 * observed on that hardware so a future regression — say, a refactor that swaps the conversion
 * direction or special-cases negative offsets — fails immediately on the affected scenario.
 *
 * The data is from [WindowsHiDpiDiagnostic] snapshots dated 2026-04-29; if Compose / JBR ever
 * change how `boundsInWindow` reports physical pixels under Windows DPI scaling, these tests will
 * need re-capturing — which is the right signal, not a maintenance burden.
 *
 * The point is *not* re-testing the math (the unit tests in [HiDpiMapperTest] do that with
 * synthetic values). It's pinning the Windows reality so we don't drift away from it accidentally.
 */
class WindowsHiDpiScenariosTest {

    @Test
    fun `Display1 at 100 pct with the panel partially off-screen left`() {
        // panelScreenX is negative because the window was dragged so its left edge fell off
        // Display1's left side (which still ends at x=0; the negative space belongs to Display0).
        // The HiDpiMapper formula must add the negative offset cleanly — it has no special-case
        // for negative panelScreenX/Y, and this test pins that.
        val center =
            composeBoundsToAwtCenter(
                left = 16.0f,
                top = 226.0f,
                right = 584.0f,
                bottom = 339.0f,
                scaleX = 1.0f,
                scaleY = 1.0f,
                panelScreenX = -227,
                panelScreenY = 571,
            )
        assertEquals(Point(73, 853), center)

        val rect =
            composeBoundsToAwtRectangle(
                left = 16.0f,
                top = 226.0f,
                right = 584.0f,
                bottom = 339.0f,
                scaleX = 1.0f,
                scaleY = 1.0f,
                panelScreenX = -227,
                panelScreenY = 571,
            )
        assertEquals(Rectangle(-211, 797, 568, 113), rect)
    }

    @Test
    fun `Display0 at 200 pct with the panel at large negative virtual-desktop X`() {
        // Display0 is the non-primary 200% monitor positioned to the left of the primary, so its
        // bounds start at x=-3840. After dragging the diagnostic window onto it, both the GC's
        // defaultTransform and Compose's LocalDensity report 2.0, and `boundsInWindow` doubles —
        // it returns Compose physical pixels, not AWT scaled units.
        val center =
            composeBoundsToAwtCenter(
                left = 32.0f,
                top = 446.0f,
                right = 1176.0f,
                bottom = 686.0f,
                scaleX = 2.0f,
                scaleY = 2.0f,
                panelScreenX = -2592,
                panelScreenY = 312,
            )
        assertEquals(Point(-2290, 595), center)

        val rect =
            composeBoundsToAwtRectangle(
                left = 32.0f,
                top = 446.0f,
                right = 1176.0f,
                bottom = 686.0f,
                scaleX = 2.0f,
                scaleY = 2.0f,
                panelScreenX = -2592,
                panelScreenY = 312,
            )
        assertEquals(Rectangle(-2576, 535, 572, 120), rect)
    }

    @Test
    fun `Display1 at 125 pct Windows scale`() {
        // 125% is a fractional scale Windows specifically supports. Compose's density also
        // reports 1.25 (no rounding to the nearest integer), and the AWT-output math matches
        // what HiDpiMapper produces.
        val center =
            composeBoundsToAwtCenter(
                left = 20.0f,
                top = 278.0f,
                right = 733.0f,
                bottom = 426.0f,
                scaleX = 1.25f,
                scaleY = 1.25f,
                panelScreenX = 471,
                panelScreenY = 597,
            )
        assertEquals(Point(772, 878), center)

        val rect =
            composeBoundsToAwtRectangle(
                left = 20.0f,
                top = 278.0f,
                right = 733.0f,
                bottom = 426.0f,
                scaleX = 1.25f,
                scaleY = 1.25f,
                panelScreenX = 471,
                panelScreenY = 597,
            )
        assertEquals(Rectangle(487, 819, 571, 119), rect)
    }

    @Test
    fun `Display1 at 150 pct Windows scale`() {
        val center =
            composeBoundsToAwtCenter(
                left = 24.0f,
                top = 336.0f,
                right = 879.0f,
                bottom = 512.0f,
                scaleX = 1.5f,
                scaleY = 1.5f,
                panelScreenX = 394,
                panelScreenY = 503,
            )
        assertEquals(Point(695, 785), center)

        val rect =
            composeBoundsToAwtRectangle(
                left = 24.0f,
                top = 336.0f,
                right = 879.0f,
                bottom = 512.0f,
                scaleX = 1.5f,
                scaleY = 1.5f,
                panelScreenX = 394,
                panelScreenY = 503,
            )
        assertEquals(Rectangle(410, 727, 570, 118), rect)
    }
}

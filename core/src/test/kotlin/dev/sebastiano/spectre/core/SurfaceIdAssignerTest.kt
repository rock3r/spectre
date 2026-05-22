@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SurfaceIdAssignerTest {

    @Test
    fun `same identity returns same id across calls`() {
        val assigner = SurfaceIdAssigner()
        val identity = Any()
        val first = assigner.assign("window", identity)
        val second = assigner.assign("window", identity)
        assertEquals(first, second)
    }

    @Test
    fun `different identities get distinct sequential ids per prefix`() {
        val assigner = SurfaceIdAssigner()
        val a = assigner.assign("window", Any())
        val b = assigner.assign("window", Any())
        val c = assigner.assign("window", Any())
        assertEquals("window:0", a)
        assertEquals("window:1", b)
        assertEquals("window:2", c)
    }

    @Test
    fun `each prefix has its own index sequence`() {
        val assigner = SurfaceIdAssigner()
        val window0 = assigner.assign("window", Any())
        val popup0 = assigner.assign("popup", Any())
        val overlay0 = assigner.assign("overlay", Any())
        assertEquals("window:0", window0)
        assertEquals("popup:0", popup0)
        assertEquals("overlay:0", overlay0)
    }

    @Test
    fun `closed identity does not recycle its index when a new identity arrives later`() {
        val assigner = SurfaceIdAssigner()
        val a = Any()
        val b = Any()
        val idA = assigner.assign("popup", a)
        val idB = assigner.assign("popup", b)
        assertEquals("popup:0", idA)
        assertEquals("popup:1", idB)
        // "a" goes away. The assigner does not know about disposal (no need to — identity will
        // simply never be asked again), so the next fresh identity must get index 2, not 0.
        val c = Any()
        val idC = assigner.assign("popup", c)
        assertEquals("popup:2", idC)
        assertNotEquals(idA, idC)
    }

    @Test
    fun `identity equality is reference-based`() {
        // Two distinct objects that share the same equals/hashCode (Strings, in this case) must
        // still get distinct ids — surface identity is the underlying object, not its value.
        val assigner = SurfaceIdAssigner()
        val left = String(charArrayOf('x'))
        val right = String(charArrayOf('x'))
        assertEquals(left, right)
        val idLeft = assigner.assign("window", left)
        val idRight = assigner.assign("window", right)
        assertNotEquals(idLeft, idRight)
    }

    @Test
    fun `composite identity caches across calls with same references`() {
        val assigner = SurfaceIdAssigner()
        val window = Any()
        val panel = Any()
        val first = assigner.assign("window", window, panel)
        val second = assigner.assign("window", window, panel)
        assertEquals(first, second)
    }

    @Test
    fun `composite identity differs when any part differs`() {
        val assigner = SurfaceIdAssigner()
        val window = Any()
        val panelA = Any()
        val panelB = Any()
        val idA = assigner.assign("window", window, panelA)
        val idB = assigner.assign("window", window, panelB)
        assertNotEquals(idA, idB)
    }

    @Test
    fun `null parts are stable across calls`() {
        val assigner = SurfaceIdAssigner()
        val window = Any()
        val first = assigner.assign("window", window, null)
        val second = assigner.assign("window", window, null)
        assertEquals(first, second)
    }
}

package dev.sebastiano.spectre.testing

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class FailureVideoConfigAbiTest {
    @Test
    fun `copy and copy default preserve scope`() {
        val bounds = Rectangle(2, 3, 40, 50)
        val configured = FailureVideoConfig().withRegion(bounds)
        val copied = configured.copy(invocationId = "next")

        assertEquals(FailureVideoScope.Region(bounds), copied.scope)
        assertNotSame(
            (configured.scope as FailureVideoScope.Region).bounds,
            (copied.scope as FailureVideoScope.Region).bounds,
        )
        assertEquals(FailureVideoScope.Display(2), FailureVideoConfig().withDisplay(2).copy().scope)
    }

    @Test
    fun `legacy constructor and copy default descriptors remain present`() {
        val methods = FailureVideoConfig::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(true, "copy\$default" in methods)
        assertEquals(
            true,
            FailureVideoConfig::class.java.declaredConstructors.any { it.parameterCount == 4 },
        )
        assertEquals(
            true,
            FailureVideoConfig::class.java.declaredConstructors.any { it.parameterCount == 6 },
        )
    }
}

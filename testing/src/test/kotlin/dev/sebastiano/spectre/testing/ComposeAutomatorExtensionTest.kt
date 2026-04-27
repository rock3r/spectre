package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class ComposeAutomatorExtensionTest {

    private val factoryCalls = mutableListOf<ComposeAutomator>()

    @JvmField
    @RegisterExtension
    val automatorExt =
        ComposeAutomatorExtension(
            factory = {
                val instance = newHeadlessAutomator()
                factoryCalls += instance
                instance
            }
        )

    @Test
    fun `automator is initialised before each test runs`() {
        // The extension runs beforeEach; touching the property here proves the lifecycle hook
        // resolved the factory and stored the instance.
        assertSame(factoryCalls.first(), automatorExt.automator)
    }

    @Test
    fun `each test gets its own automator instance`() {
        // The extension recreates the instance before every method, so factoryCalls.size for
        // the *current* execution is exactly 1.
        assertEquals(1, factoryCalls.size)
    }
}

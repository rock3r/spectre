package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.SemanticsReader
import dev.sebastiano.spectre.core.WindowTracker
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.Test

class ComposeAutomatorRuleTest {

    private val factoryCalls = mutableListOf<ComposeAutomator>()

    @get:Rule
    val automatorRule =
        ComposeAutomatorRule(
            factory = {
                val instance = newHeadlessAutomator()
                factoryCalls += instance
                instance
            }
        )

    @Test
    fun `automator is initialised before the test runs`() {
        // Touching the property must succeed once the rule's before() has run.
        assertSame(factoryCalls.first(), automatorRule.automator)
    }

    @Test
    fun `each test gets its own automator instance`() {
        // The rule recreates the instance before every method, so factoryCalls.size for the
        // *current* execution is exactly 1.
        assertEquals(1, factoryCalls.size)
    }

    @Test
    fun `accessing automator after the rule tears down throws`() {
        // We can't observe after() from inside the test body, so simulate the access pattern
        // with a manually-driven rule.
        val standalone = ComposeAutomatorRule(factory = ::newHeadlessAutomator)
        standalone.before()
        standalone.after()

        val error = assertFailsWith<IllegalStateException> { standalone.automator }
        assertEquals(
            "ComposeAutomatorRule.automator accessed outside of a running test",
            error.message,
        )
    }
}

internal fun newHeadlessAutomator(): ComposeAutomator =
    ComposeAutomator.inProcess(
        windowTracker = WindowTracker(),
        semanticsReader = SemanticsReader(),
        robotDriver = RobotDriver.headless(),
    )

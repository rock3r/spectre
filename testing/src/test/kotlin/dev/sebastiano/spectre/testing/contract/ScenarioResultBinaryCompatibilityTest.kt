package dev.sebastiano.spectre.testing.contract

import dev.sebastiano.spectre.testing.contract.AutomatorContractCorpus.ScenarioOutcome
import dev.sebastiano.spectre.testing.contract.AutomatorContractCorpus.ScenarioResult
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ScenarioResultBinaryCompatibilityTest {
    @Test
    fun `retains released boolean data class JVM descriptors`() {
        val type = ScenarioResult::class.java
        type.getConstructor(
            String::class.java,
            AutomatorTransport::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
        )
        assertEquals(
            Boolean::class.javaPrimitiveType,
            type.getDeclaredMethod("component3").returnType,
        )
        type.getDeclaredMethod(
            "copy",
            String::class.java,
            AutomatorTransport::class.java,
            Boolean::class.javaPrimitiveType,
            String::class.java,
        )
        val copyDefault =
            type.getDeclaredMethod(
                "copy\$default",
                type,
                String::class.java,
                AutomatorTransport::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
                Any::class.java,
            )
        assertTrue(Modifier.isStatic(copyDefault.modifiers))
    }

    @Test
    fun `released Kotlin destructuring and copy source remains valid`() {
        val original = ScenarioResult("old", AutomatorTransport.InProcess, true, "detail")
        val (_, _, passed: Boolean) = original
        assertTrue(passed)
        assertFalse(original.copy(passed = false).passed)
        assertEquals("changed", original.copy(detail = "changed").detail)
        assertEquals(original, original.copy())
    }

    @Test
    fun `typed outcomes preserve skipped copies and support explicit transitions`() {
        val skipped =
            ScenarioResult(
                "skip",
                AutomatorTransport.InProcess,
                ScenarioOutcome.Skipped,
                "reason",
            )
        assertFalse(skipped.passed)
        assertEquals(ScenarioOutcome.Skipped, skipped.copy().outcome)
        assertEquals(
            ScenarioOutcome.Skipped,
            skipped.copy(id = "renamed", detail = "updated").outcome,
        )
        assertEquals(ScenarioOutcome.Passed, skipped.withOutcome(ScenarioOutcome.Passed).outcome)
        assertEquals(ScenarioOutcome.Failed, skipped.withOutcome(ScenarioOutcome.Failed).outcome)
        assertEquals(ScenarioOutcome.Skipped, skipped.withOutcome(ScenarioOutcome.Skipped).outcome)
        assertEquals(
            ScenarioOutcome.Skipped,
            ScenarioResult("skip", AutomatorTransport.InProcess, false)
                .withOutcome(ScenarioOutcome.Skipped)
                .outcome,
        )
        assertNotEquals(skipped, skipped.withOutcome(ScenarioOutcome.Failed))
    }
}

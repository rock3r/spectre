package dev.sebastiano.spectre.testing

import kotlin.test.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Source-compatibility guard for the idiomatic trailing-lambda construction form.
 *
 * `ComposeAutomatorExtension { … }` and `ComposeAutomatorRule { … }` are the shapes every pre-0.3
 * consumer wrote, and they are what Kotlin's trailing-lambda convention leads callers to write.
 * When `failureArtifacts` was introduced it was appended *after* the `factory` parameter, which
 * silently moved the trailing lambda onto a non-function parameter and broke every such call site
 * with "argument type mismatch: … but 'FailureArtifactsConfig' was expected". Spectre's own tests
 * all pass `factory = …` by name, so nothing caught it.
 *
 * This test does its real work at *compile* time: if the function-typed parameter ever stops being
 * last, this file no longer compiles. Keep the trailing-lambda form here — rewriting it to a named
 * argument would defeat the entire point of the guard.
 */
class AutomatorFactoryTrailingLambdaTest {

    private val extensionInstances = mutableListOf<Any>()

    @JvmField
    @RegisterExtension
    val automatorExt = ComposeAutomatorExtension {
        val instance = newHeadlessAutomator()
        extensionInstances += instance
        instance
    }

    @Test
    fun `extension accepts a trailing-lambda factory`() {
        assertSame(extensionInstances.first(), automatorExt.automator)
    }

    @Test
    fun `rule accepts a trailing-lambda factory`() {
        // JUnit 4 rule constructed directly (not via @Rule) so this stays a JUnit 5 test class;
        // constructing it is enough to pin the source shape.
        val rule = ComposeAutomatorRule { newHeadlessAutomator() }
        rule
            .apply(
                object : org.junit.runners.model.Statement() {
                    override fun evaluate() {
                        assertSame(rule.automator, rule.automator)
                    }
                },
                org.junit.runner.Description.createTestDescription(
                    AutomatorFactoryTrailingLambdaTest::class.java,
                    "rule accepts a trailing-lambda factory",
                ),
            )
            .evaluate()
    }
}

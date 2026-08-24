@file:OptIn(
    dev.sebastiano.spectre.core.InternalSpectreApi::class,
    dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class,
)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.AutomatorInputLease
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InputLeaseOptions
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.runner.Description
import org.junit.runners.model.Statement

class InputIsolationLifecycleTest {

    @Test
    fun `JUnit 5 per-test lease spans factory and lifecycle`() {
        val events = mutableListOf<String>()
        val leaseFactory = recordingLeaseFactory(events)
        val extension =
            ComposeAutomatorExtension(
                inputIsolation = InputIsolationConfig.perTest(),
                leaseFactory = leaseFactory,
                factory = {
                    events += "factory"
                    newHeadlessAutomator()
                },
            )
        val context = recordingContext("extension test")

        extension.beforeEach(context)
        events += "body"
        extension.afterEach(context)

        assertEquals(
            listOf(
                "acquire:InputIsolationLifecycleTest#extensionTestFixture",
                "enter-factory-lease",
                "factory",
                "exit-factory-lease",
                "bind",
                "body",
                "unbind",
                "release",
            ),
            events,
        )
    }

    @Test
    fun `JUnit 5 factory failure releases an acquired lease`() {
        val events = mutableListOf<String>()
        val extension =
            ComposeAutomatorExtension(
                inputIsolation = InputIsolationConfig.perTest(),
                leaseFactory = recordingLeaseFactory(events),
                factory = {
                    events += "factory"
                    error("factory failed")
                },
            )

        assertFailsWith<IllegalStateException> { extension.beforeEach(recordingContext("failure")) }

        assertEquals(
            listOf(
                "acquire:InputIsolationLifecycleTest#extensionTestFixture",
                "enter-factory-lease",
                "factory",
                "exit-factory-lease",
                "release",
            ),
            events,
        )
    }

    @Test
    fun `JUnit 4 per-test lease wraps factory body and teardown`() {
        val events = mutableListOf<String>()
        val rule =
            ComposeAutomatorRule(
                inputIsolation = InputIsolationConfig.perTest(),
                leaseFactory = recordingLeaseFactory(events),
                factory = {
                    events += "factory"
                    newHeadlessAutomator()
                },
            )
        val body =
            object : Statement() {
                override fun evaluate() {
                    events += "body"
                }
            }

        rule.apply(body, Description.createTestDescription(javaClass, "rule test")).evaluate()

        assertEquals(
            listOf(
                "acquire:InputIsolationLifecycleTest#rule test",
                "enter-factory-lease",
                "factory",
                "exit-factory-lease",
                "bind",
                "body",
                "unbind",
                "release",
            ),
            events,
        )
    }

    @Test
    fun `preserving default per-interaction mode does not acquire a whole-test lease`() {
        val events = mutableListOf<String>()
        val rule =
            ComposeAutomatorRule(
                leaseFactory = recordingLeaseFactory(events),
                factory = { newHeadlessAutomator() },
            )
        val body =
            object : Statement() {
                override fun evaluate(): Unit = Unit
            }

        rule.apply(body, Description.createTestDescription(javaClass, "compatibility")).evaluate()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `explicit default factory enables per-interaction operation leases`() {
        val selectedFactories = mutableListOf<String>()
        val legacyFactory = {
            selectedFactories += "legacy"
            newHeadlessAutomator()
        }
        val coordinatedFactory = {
            selectedFactories += "coordinated"
            newHeadlessAutomator()
        }

        listOf(
                InputIsolationConfig.perInteraction() to "coordinated",
                InputIsolationConfig.perTest() to "legacy",
                InputIsolationConfig.auto() to "legacy",
                InputIsolationConfig.off() to "legacy",
            )
            .forEach { (config, expectedFactory) ->
                defaultAutomatorFactory(
                        inputIsolation = config,
                        legacyFactory = legacyFactory,
                        coordinatedFactory = coordinatedFactory,
                    )
                    .invoke()

                assertEquals(expectedFactory, selectedFactories.removeLast())
            }
    }

    private fun recordingContext(displayName: String): RecordingExtensionContext =
        RecordingExtensionContext(
            failure = null,
            testClass = javaClass,
            methodName = fixtureMethod().name,
            uniqueId = "[test:$displayName]",
        )

    private fun fixtureMethod(): Method = javaClass.getDeclaredMethod("extensionTestFixture")

    @Suppress("unused") private fun extensionTestFixture(): Unit = Unit
}

private fun recordingLeaseFactory(events: MutableList<String>): InputTestLeaseFactory =
    InputTestLeaseFactory { _: InputLeaseOptions, ownerLabel: String ->
        events += "acquire:$ownerLabel"
        object : AutomatorInputLease {
            override fun <T> withLease(block: () -> T): T {
                events += "enter-factory-lease"
                return try {
                    block()
                } finally {
                    events += "exit-factory-lease"
                }
            }

            override fun bind(automator: ComposeAutomator): AutoCloseable {
                events += "bind"
                return AutoCloseable { events += "unbind" }
            }

            override fun close() {
                events += "release"
            }
        }
    }

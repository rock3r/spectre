@file:OptIn(
    dev.sebastiano.spectre.core.InternalSpectreApi::class,
    dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class,
)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.AutomatorInputLease
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InputLeaseOptions
import java.lang.reflect.Method
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    fun `per-interaction default factory constructs a synthetic driver`() {
        val source =
            Path.of("src/main/kotlin/dev/sebastiano/spectre/testing/InputIsolationConfig.kt")
                .readText()
        assertTrue(
            source.contains("RobotDriver.synthetic(InputLeasePolicy.Required)"),
            "defaultManagedAutomatorFactory must default to RobotDriver.synthetic, not real OS Robot",
        )
        assertFalse(
            source.contains("val driver = RobotDriver(InputLeasePolicy.Required)"),
            "defaultManagedAutomatorFactory must not construct the real-OS RobotDriver(policy)",
        )
    }

    @Test
    fun `explicit default factory owns per-interaction driver only`() {
        val managedFactory =
            defaultManagedAutomatorFactory(
                inputIsolation = InputIsolationConfig.perInteraction(),
                coordinatedFactory = { ManagedAutomator(newHeadlessAutomator(), AutoCloseable {}) },
            )

        assertNotNull(managedFactory)
        assertNull(defaultManagedAutomatorFactory(InputIsolationConfig.perTest()))
        assertNull(defaultManagedAutomatorFactory(InputIsolationConfig.auto()))
        assertNull(defaultManagedAutomatorFactory(InputIsolationConfig.off()))
    }

    @Test
    fun `managed per-interaction driver closes with isolation session`() {
        val events = mutableListOf<String>()
        val session =
            InputIsolationSession(
                config = InputIsolationConfig.perInteraction(),
                acquireBeforeAutoFactory = true,
                ownerLabel = "managed",
                leaseFactory = recordingLeaseFactory(events),
            )

        session.createAutomator(
            factory = { error("legacy factory must not run") },
            managedFactory =
                ManagedAutomatorFactory {
                    ManagedAutomator(
                        automator = newHeadlessAutomator(),
                        resource = AutoCloseable { events += "close-driver" },
                    )
                },
        )
        session.close()

        assertEquals(listOf("close-driver"), events)
    }

    @Test
    fun `JUnit wrappers close their managed per-interaction drivers`() {
        val events = mutableListOf<String>()
        val extension =
            ComposeAutomatorExtension(
                inputIsolation = InputIsolationConfig.perInteraction(),
                managedFactory = managedFactory("extension", events),
                factory = { error("legacy extension factory must not run") },
            )
        val context = recordingContext("managed extension")
        extension.beforeEach(context)
        extension.afterEach(context)

        val rule =
            ComposeAutomatorRule(
                inputIsolation = InputIsolationConfig.perInteraction(),
                managedFactory = managedFactory("rule", events),
                factory = { error("legacy rule factory must not run") },
            )
        val body =
            object : Statement() {
                override fun evaluate(): Unit = Unit
            }
        rule.apply(body, Description.createTestDescription(javaClass, "managed rule")).evaluate()

        assertEquals(listOf("close-extension", "close-rule"), events)
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

private fun managedFactory(name: String, events: MutableList<String>): ManagedAutomatorFactory =
    ManagedAutomatorFactory {
        ManagedAutomator(
            automator = newHeadlessAutomator(),
            resource = AutoCloseable { events += "close-$name" },
        )
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

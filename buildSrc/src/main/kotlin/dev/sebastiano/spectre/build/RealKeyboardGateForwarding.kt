package dev.sebastiano.spectre.build

import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/**
 * Gradle property that turns the Robot-backed real-keyboard test paths on or off (#444, #449).
 *
 * Must stay equal to `RealKeyboardGate.GRADLE_PROPERTY` in `:testing`.
 */
const val REAL_KEYBOARD_GRADLE_PROPERTY = "spectre.agent.realKeyboard"

/**
 * System property the forked test JVM actually reads.
 *
 * Must stay equal to `RealKeyboardGate.ENABLE_PROP` in `:testing`.
 */
const val REAL_KEYBOARD_SYSTEM_PROPERTY = "dev.sebastiano.spectre.agent.realKeyboard"

/**
 * Forwards the real-keyboard opt-in from the Gradle CLI into forked test workers.
 *
 * Two suites send real OS key events at a spawned Compose fixture — `AgentAttachIntegrationTest`'s
 * `typeText` subpath and the contract corpus' `press-key-tab-after-focus` scenario. Both need the
 * fixture window to own OS keyboard focus, so `RealKeyboardGate` turns them off by default on
 * developer machines and on by default on CI. Apply this to the test tasks of every module whose
 * tests can reach either path: `:agent`, `:server`, and `:testing`.
 *
 * Gradle CLI `-D…` alone only reaches the daemon, so the value is re-emitted as an explicit `-D` on
 * the worker command line. Both the property and the `CI` environment variable are declared as task
 * inputs: with no property set the gate flips purely because `CI` changed, so without `CI` as an
 * input a CI run could stay UP-TO-DATE on (or restore from the build cache) a developer-mode result
 * where the keyboard paths were skipped, silently dropping the coverage CI is supposed to provide.
 */
fun Test.forwardRealKeyboardGate(providers: ProviderFactory) {
    val property =
        providers
            .gradleProperty(REAL_KEYBOARD_GRADLE_PROPERTY)
            .orElse(providers.systemProperty(REAL_KEYBOARD_SYSTEM_PROPERTY))
            .orElse("")
    inputs.property(REAL_KEYBOARD_GRADLE_PROPERTY, property)
    inputs.property(
        "$REAL_KEYBOARD_GRADLE_PROPERTY.ci",
        providers.environmentVariable("CI").orElse(""),
    )
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { realKeyboardJvmArgs(property.get()) }
    )
}

/**
 * Worker JVM arguments for a real-keyboard property value.
 *
 * A blank value means "not set": forward nothing and let the gate fall back to the `CI`
 * environment variable. Any other value is forwarded verbatim, including `false`, which is how CI
 * turns the keyboard paths off.
 */
internal fun realKeyboardJvmArgs(propertyValue: String): List<String> =
    propertyValue.takeIf { it.isNotBlank() }?.let { listOf("-D$REAL_KEYBOARD_SYSTEM_PROPERTY=$it") }
        ?: emptyList()

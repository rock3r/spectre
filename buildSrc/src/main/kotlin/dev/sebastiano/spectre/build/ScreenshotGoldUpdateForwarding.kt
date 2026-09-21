package dev.sebastiano.spectre.build

import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/** Must stay equal to [dev.sebastiano.spectre.testing.ScreenshotUpdateMode.GRADLE_PROPERTY]. */
const val SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY = "spectre.updateScreenshotGolds"

/** Must stay equal to [dev.sebastiano.spectre.testing.ScreenshotUpdateMode.SYSTEM_PROPERTY]. */
const val SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY =
    "dev.sebastiano.spectre.testing.updateScreenshotGolds"

/** Must stay equal to [dev.sebastiano.spectre.testing.ScreenshotUpdateMode.ENV]. */
const val SCREENSHOT_GOLD_UPDATE_ENV = "SPECTRE_UPDATE_SCREENSHOT_GOLDS"

/**
 * Forwards `-Pspectre.updateScreenshotGolds` into forked test workers. The environment variable
 * `SPECTRE_UPDATE_SCREENSHOT_GOLDS` is read directly by the test JVM and does not need forwarding.
 *
 * When the resolved update flag is true, the Test task is forced to execute: Gradle must not skip
 * it as UP-TO-DATE or restore it FROM-CACHE. Moving a window to another-density monitor is not a
 * Gradle input, so a cached equivalent run would otherwise leave golds stale.
 */
fun Test.forwardScreenshotGoldUpdateMode(providers: ProviderFactory) {
    val property =
        providers
            .gradleProperty(SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY)
            .orElse(providers.systemProperty(SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY))
            .orElse("")
    val env = providers.environmentVariable(SCREENSHOT_GOLD_UPDATE_ENV).orElse("")
    inputs.property(SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY, property)
    inputs.property("$SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY.env", env)
    val updateEnabled =
        providers.provider { screenshotGoldUpdateModeEnabled(property.get(), env.get()) }
    outputs.upToDateWhen { !updateEnabled.get() }
    outputs.cacheIf { !updateEnabled.get() }
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { screenshotGoldUpdateJvmArgs(property.get()) }
    )
}

internal data class ScreenshotGoldUpdateExecutionPolicy(
    val disableUpToDate: Boolean,
    val disableCache: Boolean,
)

internal fun screenshotGoldUpdateExecutionPolicy(
    updateEnabled: Boolean
): ScreenshotGoldUpdateExecutionPolicy =
    ScreenshotGoldUpdateExecutionPolicy(
        disableUpToDate = updateEnabled,
        disableCache = updateEnabled,
    )

internal fun screenshotGoldUpdateModeEnabled(propertyValue: String, envValue: String): Boolean {
    val fromProperty = propertyValue.takeIf { it.isNotBlank() }
    if (fromProperty != null) return fromProperty.equals("true", ignoreCase = true)
    return envValue.equals("true", ignoreCase = true)
}

internal fun screenshotGoldUpdateJvmArgs(propertyValue: String): List<String> =
    propertyValue.takeIf { it.isNotBlank() }?.let {
        listOf("-D$SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY=$it")
    } ?: emptyList()

package dev.sebastiano.spectre.build

import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/** Must stay equal to [dev.sebastiano.spectre.testing.ScreenshotUpdateMode.GRADLE_PROPERTY]. */
const val SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY = "spectre.updateScreenshotGolds"

/** Must stay equal to [dev.sebastiano.spectre.testing.ScreenshotUpdateMode.SYSTEM_PROPERTY]. */
const val SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY =
    "dev.sebastiano.spectre.testing.updateScreenshotGolds"

/**
 * Forwards `-Pspectre.updateScreenshotGolds` into forked test workers. The environment variable
 * `SPECTRE_UPDATE_SCREENSHOT_GOLDS` is read directly by the test JVM and does not need forwarding.
 */
fun Test.forwardScreenshotGoldUpdateMode(providers: ProviderFactory) {
    val property =
        providers
            .gradleProperty(SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY)
            .orElse(providers.systemProperty(SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY))
            .orElse("")
    inputs.property(SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY, property)
    inputs.property(
        "$SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY.env",
        providers.environmentVariable("SPECTRE_UPDATE_SCREENSHOT_GOLDS").orElse(""),
    )
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { screenshotGoldUpdateJvmArgs(property.get()) }
    )
}

internal fun screenshotGoldUpdateJvmArgs(propertyValue: String): List<String> =
    propertyValue.takeIf { it.isNotBlank() }?.let {
        listOf("-D$SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY=$it")
    } ?: emptyList()

package dev.sebastiano.spectre.cli

import java.util.Properties

/**
 * Build-time metadata embedded in the CLI classpath / packaged distribution.
 *
 * [version] tracks Gradle `project.version` (`VERSION_NAME`), including release overrides via
 * `-PVERSION_NAME=…`. MCP `serverInfo.version` and any other user-facing version strings must use
 * this value rather than a hardcoded constant.
 */
public object SpectreBuildMetadata {
    /** Spectre version string for this build (e.g. `0.1.0-SNAPSHOT` or `0.5.0`). */
    public val version: String by lazy { loadVersion() }

    private fun loadVersion(): String {
        val stream =
            SpectreBuildMetadata::class.java.getResourceAsStream(RESOURCE_PATH)
                ?: error("Missing classpath resource $RESOURCE_PATH (CLI build metadata)")
        val properties = stream.use { input -> Properties().apply { load(input) } }
        val value = properties.getProperty(VERSION_KEY)?.trim().orEmpty()
        require(value.isNotEmpty() && !value.contains("\${")) {
            "spectre-build.properties version must be expanded from project.version; was: '$value'"
        }
        return value
    }

    private const val RESOURCE_PATH: String = "/spectre-build.properties"
    private const val VERSION_KEY: String = "version"
}

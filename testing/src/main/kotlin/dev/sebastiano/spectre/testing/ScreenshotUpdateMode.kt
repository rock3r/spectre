package dev.sebastiano.spectre.testing

/**
 * Opt-in gold rewrite for screenshot assertions.
 *
 * Off by default. Enable with `SPECTRE_UPDATE_SCREENSHOT_GOLDS=true` or
 * `-Pspectre.updateScreenshotGolds=true` (forwarded to the test JVM as [SYSTEM_PROPERTY]). When
 * both are set, the Gradle/system property wins, including an explicit `false` that disables a true
 * environment variable.
 */
public object ScreenshotUpdateMode {

    public const val ENV: String = "SPECTRE_UPDATE_SCREENSHOT_GOLDS"
    public const val GRADLE_PROPERTY: String = "spectre.updateScreenshotGolds"
    public const val SYSTEM_PROPERTY: String =
        "dev.sebastiano.spectre.testing.updateScreenshotGolds"

    public fun isEnabled(
        property: String? = System.getProperty(SYSTEM_PROPERTY),
        env: String? = System.getenv(ENV),
    ): Boolean {
        val fromProperty = property?.takeIf { it.isNotBlank() }
        if (fromProperty != null) return fromProperty.equals("true", ignoreCase = true)
        return env.equals("true", ignoreCase = true)
    }
}

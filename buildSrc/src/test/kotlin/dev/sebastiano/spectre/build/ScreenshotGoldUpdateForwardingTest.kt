package dev.sebastiano.spectre.build

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit coverage for screenshot-gold update-mode forwarding used by `:testing`, `:agent`, and
 * `:server` test tasks.
 *
 * Gradle's CLI `-P` form never reaches a forked Test worker on its own, so the build has to
 * translate it into a `-D` on the worker command line. If the two spellings drift apart, `-P`
 * silently does nothing and only the environment variable still works.
 */
class ScreenshotGoldUpdateForwardingTest {

    @Test
    fun `a blank property forwards nothing`() {
        assertEquals(emptyList<String>(), screenshotGoldUpdateJvmArgs(""))
        assertEquals(emptyList<String>(), screenshotGoldUpdateJvmArgs("   "))
    }

    @Test
    fun `a set property is forwarded to the worker as a system property`() {
        assertEquals(
            listOf("-D$SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY=true"),
            screenshotGoldUpdateJvmArgs("true"),
        )
        assertEquals(
            listOf("-D$SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY=false"),
            screenshotGoldUpdateJvmArgs("false"),
        )
    }

    @Test
    fun `update mode is true only for an explicit true property or env`() {
        assertEquals(false, screenshotGoldUpdateModeEnabled(propertyValue = "", envValue = ""))
        assertEquals(false, screenshotGoldUpdateModeEnabled(propertyValue = "   ", envValue = ""))
        assertEquals(true, screenshotGoldUpdateModeEnabled(propertyValue = "true", envValue = ""))
        assertEquals(true, screenshotGoldUpdateModeEnabled(propertyValue = "TRUE", envValue = ""))
        assertEquals(true, screenshotGoldUpdateModeEnabled(propertyValue = "", envValue = "true"))
        assertEquals(
            false,
            screenshotGoldUpdateModeEnabled(propertyValue = "false", envValue = "true"),
        )
        assertEquals(
            true,
            screenshotGoldUpdateModeEnabled(propertyValue = "true", envValue = "false"),
        )
        assertEquals(false, screenshotGoldUpdateModeEnabled(propertyValue = "yes", envValue = ""))
    }

    @Test
    fun `update mode forces a Test rerun and disables build-cache reuse`() {
        val enabled = screenshotGoldUpdateExecutionPolicy(updateEnabled = true)
        assertEquals(true, enabled.disableUpToDate)
        assertEquals(true, enabled.disableCache)
        val disabled = screenshotGoldUpdateExecutionPolicy(updateEnabled = false)
        assertEquals(false, disabled.disableUpToDate)
        assertEquals(false, disabled.disableCache)
    }

    @Test
    fun `the forwarded names match the knobs the test JVM actually reads`() {
        val source =
            repoRoot()
                .resolve(
                    "testing/src/main/kotlin/dev/sebastiano/spectre/testing/ScreenshotUpdateMode.kt"
                )
                .readText()
        assertTrue(
            source.contains("const val SYSTEM_PROPERTY") &&
                source.contains("\"$SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY\""),
            "ScreenshotUpdateMode.SYSTEM_PROPERTY no longer matches " +
                "SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY ($SCREENSHOT_GOLD_UPDATE_SYSTEM_PROPERTY)",
        )
        assertTrue(
            source.contains("const val GRADLE_PROPERTY") &&
                source.contains("\"$SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY\""),
            "ScreenshotUpdateMode.GRADLE_PROPERTY no longer matches " +
                "SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY ($SCREENSHOT_GOLD_UPDATE_GRADLE_PROPERTY)",
        )
        assertTrue(
            source.contains("const val ENV") && source.contains("\"$SCREENSHOT_GOLD_UPDATE_ENV\""),
            "ScreenshotUpdateMode.ENV no longer matches SCREENSHOT_GOLD_UPDATE_ENV " +
                "($SCREENSHOT_GOLD_UPDATE_ENV)",
        )
        val forwarding =
            repoRoot()
                .resolve(
                    "buildSrc/src/main/kotlin/dev/sebastiano/spectre/build/" +
                        "ScreenshotGoldUpdateForwarding.kt"
                )
                .readText()
        assertTrue(
            forwarding.contains("outputs.upToDateWhen") && forwarding.contains("outputs.cacheIf"),
            "update mode must disable UP-TO-DATE skipping and build-cache reuse",
        )
    }

    private fun repoRoot(): File {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (File(candidate, "settings.gradle.kts").isFile && File(candidate, "testing").isDirectory) {
                return candidate
            }
            candidate = candidate.parentFile
        }
        error("Could not locate the Spectre repo root from ${File("").absolutePath}")
    }
}

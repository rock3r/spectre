package dev.sebastiano.spectre.recording.screencapturekit

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural contracts for the helper app bundle (#190) and release signing identity expectations
 * (#191). Does not call Apple's notary service.
 */
class HelperAppBundleContractTest {

    @Test
    fun `bundle identity is stable for TCC and release signing`() {
        assertEquals("dev.sebastiano.spectre.screencapture", HelperAppBundle.BUNDLE_ID)
        assertEquals("SpectreCaptureHelper.app", HelperAppBundle.APP_DIR_NAME)
        assertEquals("Spectre Capture Helper", HelperAppBundle.DISPLAY_NAME)
        assertEquals(
            "Contents/MacOS/spectre-screencapture",
            HelperAppBundle.EXECUTABLE_RELATIVE_PATH,
        )
        assertEquals("native/macos/SpectreCaptureHelper.app", HelperAppBundle.RESOURCE_ROOT)
    }

    @Test
    fun `checked-in Info-plist template matches public HelperAppBundle constants`() {
        // Walk up from the test resources / cwd is unreliable under Gradle; resolve from
        // user.dir (project root for :recording tests is the recording module directory).
        val moduleDir = java.io.File(System.getProperty("user.dir"))
        val infoPlist =
            moduleDir.resolve("native/macos/AppBundle/Info.plist").toPath().also { path ->
                assertTrue(
                    Files.isRegularFile(path),
                    "Expected AppBundle Info.plist at $path (cwd=${moduleDir.absolutePath})",
                )
            }
        val text = infoPlist.readText()
        assertTrue(text.contains(HelperAppBundle.BUNDLE_ID), text)
        assertTrue(text.contains(HelperAppBundle.DISPLAY_NAME), text)
        assertTrue(text.contains(HelperAppBundle.EXECUTABLE_NAME), text)
        assertTrue(text.contains("<key>LSUIElement</key>"), text)
        assertTrue(text.contains("<true/>"), "LSUIElement must be true")
        assertTrue(text.contains("AppIcon"), "Icon file entry required for Settings row")
    }
}

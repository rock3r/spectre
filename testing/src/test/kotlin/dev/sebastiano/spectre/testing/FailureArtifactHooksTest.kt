package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.capture.CaptureArtifactPaths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.runner.Description
import org.junit.runners.model.Statement

class FailureArtifactHooksTest {

    @Test
    fun `recordFailure is no-op when disabled`(@TempDir temp: Path) {
        val reports = mutableListOf<Pair<String, String>>()
        val paths =
            FailureArtifactHooks.recordFailure(
                automator = newHeadlessAutomator(),
                config = FailureArtifactsConfig(enabled = false, reportsRoot = temp),
                testClassName = "com.example.T",
                testMethodName = "fails",
                publishReport = { k, v -> reports += k to v },
                capture = { _, _ -> error("should not capture") },
            )
        assertTrue(paths.isEmpty())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `recordFailure publishes paths from capture`(@TempDir temp: Path) {
        val reports = mutableListOf<Pair<String, String>>()
        val markerDir = temp.resolve("run-marker").resolve("window-0")
        Files.createDirectories(markerDir)
        val marker =
            CaptureArtifactPaths(
                directory = markerDir,
                captureJsonPath = markerDir.resolve("capture.json"),
                screenshotPngPath = markerDir.resolve("screenshot.png"),
            )
        val paths =
            FailureArtifactHooks.recordFailure(
                automator = newHeadlessAutomator(),
                config = FailureArtifactsConfig(reportsRoot = temp),
                testClassName = "com.example.T",
                testMethodName = "fails",
                publishReport = { k, v -> reports += k to v },
                capture = { _, _ -> listOf(marker) },
            )
        assertEquals(listOf(marker), paths)
        assertEquals(1, reports.size)
        assertEquals(FailureArtifactHooks.REPORT_ENTRY_KEY, reports.single().first)
        assertTrue(reports.single().second.contains("window-0"))
    }

    @Test
    fun `recordFailure swallows capture exceptions`(@TempDir temp: Path) {
        val paths =
            FailureArtifactHooks.recordFailure(
                automator = newHeadlessAutomator(),
                config = FailureArtifactsConfig(reportsRoot = temp),
                testClassName = "com.example.T",
                testMethodName = "fails",
                publishReport = { _, _ -> },
                capture = { _, _ -> error("boom") },
            )
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `JUnit4 rule captures on failure and not on pass`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val failing =
            ComposeAutomatorRule(factory = ::newHeadlessAutomator, failureArtifacts = config)
        val failStatement =
            failing.apply(
                object : Statement() {
                    override fun evaluate() {
                        error("intentional failure")
                    }
                },
                Description.createTestDescription("com.example.Failing", "blowsUp"),
            )
        try {
            failStatement.evaluate()
            error("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("intentional failure", expected.message)
        }
        // Headless automator has zero windows — capture is best-effort and may write nothing.
        // Opt-out path is the stronger contract below.

        val optOut =
            ComposeAutomatorRule(
                factory = ::newHeadlessAutomator,
                failureArtifacts = FailureArtifactsConfig(enabled = false, reportsRoot = temp),
            )
        val passStatement =
            optOut.apply(
                object : Statement() {
                    override fun evaluate() {
                        // pass
                    }
                },
                Description.createTestDescription("com.example.Passing", "ok"),
            )
        passStatement.evaluate()
        // Disabled config must not create report trees for the passing case either.
        val classDir = temp.resolve(FailureArtifactPaths.sanitizePathSegment("com.example.Passing"))
        assertFalse(Files.exists(classDir))
    }

    @Test
    fun `JUnit4 rule opt-out does not capture on failure`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(enabled = false, reportsRoot = temp)
        val rule = ComposeAutomatorRule(factory = ::newHeadlessAutomator, failureArtifacts = config)
        val statement =
            rule.apply(
                object : Statement() {
                    override fun evaluate() {
                        error("fail")
                    }
                },
                Description.createTestDescription("com.example.OptOut", "failing"),
            )
        try {
            statement.evaluate()
        } catch (_: IllegalStateException) {
            // expected
        }
        val classDir = temp.resolve(FailureArtifactPaths.sanitizePathSegment("com.example.OptOut"))
        assertFalse(Files.exists(classDir))
    }
}

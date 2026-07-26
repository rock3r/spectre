package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.capture.CaptureArtifactsWriter
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.FailureArtifactsConfig
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Live acceptance for failure artifacts: when a Spectre-driven JUnit test fails, the rule captures
 * `capture.json` + `screenshot.png` for known windows **after** the failure and **before** the
 * automator is cleared — while the sample UI is still open.
 *
 * This drives the public [ComposeAutomatorRule] statement path (not a private helper) so the
 * artifact layout matches what consumers get on a real intentional fail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailureArtifactsValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre failure-artifacts validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `JUnit 4 rule writes capture json and png on failure while windows are open`(
        @TempDir reportsRoot: Path
    ): Unit = runBlocking {
        // Settle the sample so capture has a non-empty tree.
        with(fixture.automator) {
            refreshWindows()
            waitForIdle()
        }
        assertTrue(fixture.automator.surfaceIds().isNotEmpty(), "fixture must track a window")

        val config = FailureArtifactsConfig(reportsRoot = reportsRoot)
        // Reuse the live fixture automator so capture hits real windows (factory is per-test).
        val rule = ComposeAutomatorRule(factory = { fixture.automator }, failureArtifacts = config)
        val statement =
            rule.apply(
                object : Statement() {
                    override fun evaluate() {
                        error("intentional failure for artifact capture")
                    }
                },
                Description.createTestDescription(
                    FailureArtifactsValidationTest::class.java.name,
                    "intentionalFail",
                ),
            )

        try {
            statement.evaluate()
            error("expected intentional failure")
        } catch (expected: IllegalStateException) {
            assertEquals("intentional failure for artifact capture", expected.message)
        }

        val jsonFiles =
            Files.walk(reportsRoot).use { stream ->
                stream
                    .filter { path ->
                        path.isRegularFile() &&
                            path.fileName.toString() == CaptureArtifactsWriter.CAPTURE_JSON_NAME
                    }
                    .toList()
            }
        assertTrue(jsonFiles.isNotEmpty(), "expected capture.json under $reportsRoot")
        for (json in jsonFiles) {
            val windowDir = json.parent
            assertTrue(windowDir.fileName.toString().startsWith("window-"), "got $windowDir")
            assertTrue(
                windowDir.parent.fileName.toString().startsWith("run-"),
                "window dir must nest under run-*: $windowDir",
            )
            val png = windowDir.resolve(CaptureArtifactsWriter.SCREENSHOT_PNG_NAME)
            assertTrue(png.isRegularFile(), "missing $png")
            assertTrue(Files.size(json) > 0, "empty $json")
            assertTrue(Files.size(png) > 0, "empty $png")
        }
        // Windows must still be trackable after capture (teardown clears the rule handle only).
        assertTrue(
            fixture.automator.surfaceIds().isNotEmpty(),
            "fixture windows must remain open after rule after()",
        )
    }

    @Test
    fun `JUnit 4 rule writes nothing on pass`(@TempDir reportsRoot: Path) {
        val config = FailureArtifactsConfig(reportsRoot = reportsRoot)
        val rule = ComposeAutomatorRule(factory = { fixture.automator }, failureArtifacts = config)
        val statement =
            rule.apply(
                object : Statement() {
                    override fun evaluate() {
                        // pass
                    }
                },
                Description.createTestDescription(
                    FailureArtifactsValidationTest::class.java.name,
                    "passing",
                ),
            )
        statement.evaluate()
        assertFalse(
            Files.walk(reportsRoot).use { stream -> stream.anyMatch { Files.isRegularFile(it) } },
            "passing tests must not write under $reportsRoot",
        )
    }

    @Test
    fun `JUnit 4 rule opt-out writes nothing on failure`(@TempDir reportsRoot: Path) {
        val config = FailureArtifactsConfig(enabled = false, reportsRoot = reportsRoot)
        val rule = ComposeAutomatorRule(factory = { fixture.automator }, failureArtifacts = config)
        val statement =
            rule.apply(
                object : Statement() {
                    override fun evaluate() {
                        error("fail with opt-out")
                    }
                },
                Description.createTestDescription(
                    FailureArtifactsValidationTest::class.java.name,
                    "optOutFail",
                ),
            )
        try {
            statement.evaluate()
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse(
            Files.walk(reportsRoot).use { stream -> stream.anyMatch { Files.isRegularFile(it) } },
            "opt-out must not write under $reportsRoot",
        )
    }
}

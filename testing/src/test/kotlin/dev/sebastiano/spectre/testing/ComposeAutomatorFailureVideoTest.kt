package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.recording.RecordingHandle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.opentest4j.TestAbortedException

/**
 * Wires failure-video policy through the real [ComposeAutomatorExtension] and
 * [ComposeAutomatorRule] entry points with a fake [FailureVideoStarter] (I/O boundary only).
 */
class ComposeAutomatorFailureVideoTest {

    @Test
    fun `extension default Off never starts recorder`(@TempDir temp: Path) {
        val starts = AtomicInteger(0)
        val extension =
            ComposeAutomatorExtension(
                failureArtifacts = FailureArtifactsConfig(enabled = false, reportsRoot = temp),
                failureVideo = FailureVideoConfig(reportsRoot = temp),
                videoStarter = { _, _ ->
                    starts.incrementAndGet()
                    error("must not start")
                },
                factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
            )
        val context =
            RecordingExtensionContext(
                failure = null,
                testClass = VideoSample::class.java,
                methodName = "passes",
            )
        extension.beforeEach(context)
        extension.afterEach(context)
        assertEquals(0, starts.get())
    }

    @Test
    fun `extension onFailureKeep keeps video on fail and deletes on pass`(@TempDir temp: Path) {
        val failRoot = temp.resolve("fail-root")
        Files.createDirectories(failRoot)
        val failExt = videoExtension(failRoot, FailureVideoPolicy.OnFailureKeep)
        val failCtx =
            RecordingExtensionContext(
                failure = AssertionError("boom"),
                testClass = VideoSample::class.java,
                methodName = "fails",
            )
        failExt.beforeEach(failCtx)
        val failPath = requireActiveVideo(failRoot)
        failExt.afterTestExecution(failCtx)
        failExt.afterEach(failCtx)
        assertTrue(Files.isRegularFile(failPath), "failure must keep video: $failPath")
        assertTrue(
            failCtx.reportEntries.any { it.containsKey(FailureVideoSession.REPORT_ENTRY_KEY) }
        )

        val passRoot = temp.resolve("pass-root")
        Files.createDirectories(passRoot)
        val passExt = videoExtension(passRoot, FailureVideoPolicy.OnFailureKeep)
        val passCtx =
            RecordingExtensionContext(
                failure = null,
                testClass = VideoSample::class.java,
                methodName = "passes",
            )
        passExt.beforeEach(passCtx)
        val passPath = requireActiveVideo(passRoot)
        passExt.afterEach(passCtx)
        assertFalse(Files.exists(passPath), "pass must delete video under onFailureKeep")
    }

    @Test
    fun `extension Always keeps video on pass`(@TempDir temp: Path) {
        val extension = videoExtension(temp, FailureVideoPolicy.Always)
        val context =
            RecordingExtensionContext(
                failure = null,
                testClass = VideoSample::class.java,
                methodName = "passes",
            )
        extension.beforeEach(context)
        val path = requireActiveVideo(temp)
        extension.afterEach(context)
        assertTrue(Files.isRegularFile(path))
    }

    @Test
    fun `extension abort does not keep video`(@TempDir temp: Path) {
        val extension = videoExtension(temp, FailureVideoPolicy.OnFailureKeep)
        val context =
            RecordingExtensionContext(
                failure = TestAbortedException("skip"),
                testClass = VideoSample::class.java,
                methodName = "assumed",
            )
        extension.beforeEach(context)
        val path = requireActiveVideo(temp)
        extension.afterTestExecution(context)
        extension.afterEach(context)
        assertFalse(Files.exists(path))
    }

    @Test
    fun `rule onFailureKeep keeps on fail deletes on pass`(@TempDir temp: Path) {
        val failRule = videoRule(temp, FailureVideoPolicy.OnFailureKeep)
        val failStatement =
            failRule.apply(
                object : Statement() {
                    override fun evaluate() {
                        error("intentional failure")
                    }
                },
                Description.createTestDescription("com.example.VideoFail", "blowsUp"),
            )
        try {
            failStatement.evaluate()
            error("expected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("intentional failure", expected.message)
        }
        val kept =
            Files.walk(temp).use { stream ->
                stream
                    .filter { it.fileName.toString() == FailureVideoSession.VIDEO_FILE_NAME }
                    .toList()
            }
        assertEquals(1, kept.size, "expected one kept video after fail")
        assertTrue(Files.isRegularFile(kept.single()))

        val passRoot = temp.resolve("pass-root")
        Files.createDirectories(passRoot)
        val passRule = videoRule(passRoot, FailureVideoPolicy.OnFailureKeep)
        passRule
            .apply(
                object : Statement() {
                    override fun evaluate() {
                        // pass
                    }
                },
                Description.createTestDescription("com.example.VideoPass", "ok"),
            )
            .evaluate()
        assertFalse(
            Files.walk(passRoot).use { s ->
                s.anyMatch { it.fileName.toString() == FailureVideoSession.VIDEO_FILE_NAME }
            }
        )
    }

    @Test
    fun `failureVideo-only constructor is source-compatible for documented form`() {
        // Construction-only guard for ComposeAutomatorExtension(failureVideo = …) and the matching
        // Rule overload. Do not run beforeEach/apply here: the default factory uses RobotDriver(),
        // which fails on headless CI. Lifecycle coverage uses headless factories elsewhere.
        val extension =
            ComposeAutomatorExtension(
                failureVideo = FailureVideoConfig(policy = FailureVideoPolicy.OnFailureKeep)
            )
        val rule =
            ComposeAutomatorRule(
                failureVideo = FailureVideoConfig(policy = FailureVideoPolicy.Always)
            )
        assertEquals("ComposeAutomatorExtension", extension::class.simpleName)
        assertEquals("ComposeAutomatorRule", rule::class.simpleName)
    }

    @Test
    fun `trailing lambda factory still compiles with failureVideo param present`() {
        // Compile-time guard (mirrors AutomatorFactoryTrailingLambdaTest): factory remains last.
        // If this file fails to compile, factory is no longer the last parameter.
        val extension = ComposeAutomatorExtension {
            ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
        }
        val rule = ComposeAutomatorRule {
            ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())
        }
        // Touch the instances so the constructions are not dead code.
        assertEquals("ComposeAutomatorExtension", extension::class.simpleName)
        assertEquals("ComposeAutomatorRule", rule::class.simpleName)
    }

    private fun videoExtension(temp: Path, policy: FailureVideoPolicy): ComposeAutomatorExtension =
        ComposeAutomatorExtension(
            failureArtifacts = FailureArtifactsConfig(enabled = false, reportsRoot = temp),
            failureVideo = FailureVideoConfig(policy = policy, reportsRoot = temp),
            videoStarter = { output, _ -> fakeStarter(output) },
            factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
        )

    private fun videoRule(temp: Path, policy: FailureVideoPolicy): ComposeAutomatorRule =
        ComposeAutomatorRule(
            failureArtifacts = FailureArtifactsConfig(enabled = false, reportsRoot = temp),
            failureVideo = FailureVideoConfig(policy = policy, reportsRoot = temp),
            videoStarter = { output, _ -> fakeStarter(output) },
            factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
        )

    private fun fakeStarter(output: Path): RecordingHandle {
        Files.createDirectories(output.parent)
        Files.writeString(output, "partial")
        return object : RecordingHandle {
            override val output: Path = output
            override var isStopped: Boolean = false
                private set

            override fun stop() {
                isStopped = true
                Files.writeString(output, "finalized-video")
            }
        }
    }

    private fun requireActiveVideo(temp: Path): Path {
        val found =
            Files.walk(temp).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString() == FailureVideoSession.VIDEO_FILE_NAME }
                    .findFirst()
                    .orElse(null)
            }
        checkNotNull(found) { "expected active video under $temp" }
        return found
    }

    class VideoSample {
        fun passes(): Long = System.nanoTime()

        fun fails(): Long = System.nanoTime()

        fun assumed(): Long = System.nanoTime()
    }
}

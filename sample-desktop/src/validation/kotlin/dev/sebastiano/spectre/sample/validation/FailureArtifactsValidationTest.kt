package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.capture.CaptureArtifactsWriter
import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.FailureArtifactsConfig
import java.awt.GraphicsEnvironment
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExecutableInvoker
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstances
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Live acceptance for failure artifacts on both JUnit runners:
 * - [ComposeAutomatorRule] Statement path (JUnit 4)
 * - [ComposeAutomatorExtension] `afterTestExecution` + report entries (JUnit 5)
 *
 * Uses a real sample Compose window so capture writes non-empty `capture.json` + `screenshot.png`
 * while windows remain open. Optional system property `spectre.205.evidenceDir` copies artifacts
 * and a manifest into that directory for manual-evidence packaging (verification scratch).
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
        settleFixture()
        val config = FailureArtifactsConfig(reportsRoot = reportsRoot)
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
                    "intentionalFailJ4",
                ),
            )

        try {
            statement.evaluate()
            error("expected intentional failure")
        } catch (expected: IllegalStateException) {
            assertEquals("intentional failure for artifact capture", expected.message)
        }

        val jsonFiles = assertArtifactsOnDisk(reportsRoot)
        assertTrue(
            fixture.automator.surfaceIds().isNotEmpty(),
            "fixture windows must remain open after rule after()",
        )
        maybeExportEvidence("junit4-rule", reportsRoot, jsonFiles, reportEntries = emptyList())
    }

    @Test
    fun `JUnit 5 extension afterTestExecution writes artifacts and report entries`(
        @TempDir reportsRoot: Path
    ): Unit = runBlocking {
        settleFixture()
        val config = FailureArtifactsConfig(reportsRoot = reportsRoot)
        val extension =
            ComposeAutomatorExtension(factory = { fixture.automator }, failureArtifacts = config)
        val context =
            LiveRecordingExtensionContext(
                failure = AssertionError("intentional failure for JUnit5 artifact capture"),
                testClass = FailureArtifactsValidationTest::class.java,
                methodName = "intentionalFailJ5Marker",
            )

        // Real extension lifecycle: beforeEach stores automator → afterTestExecution captures.
        extension.beforeEach(context)
        extension.afterTestExecution(context)
        extension.afterEach(context)

        val jsonFiles = assertArtifactsOnDisk(reportsRoot)
        assertTrue(
            context.reportEntries.isNotEmpty(),
            "JUnit 5 must publish spectre.failureArtifact report entries, got ${context.reportEntries}",
        )
        for (entry in context.reportEntries) {
            // Public contract: ComposeAutomatorExtension publishes under this key (see KDoc).
            assertEquals(JUNIT5_FAILURE_ARTIFACT_REPORT_KEY, entry.keys.single())
            val path = Path.of(entry.values.single())
            assertTrue(Files.isDirectory(path), "report entry must be a window directory: $path")
            assertTrue(
                path.fileName.toString().startsWith("window-"),
                "report entry should point at window-*: $path",
            )
            assertTrue(
                Files.isRegularFile(path.resolve(CaptureArtifactsWriter.CAPTURE_JSON_NAME)),
                "window dir must contain capture.json: $path",
            )
        }
        assertTrue(
            fixture.automator.surfaceIds().isNotEmpty(),
            "fixture windows must remain open after extension afterEach",
        )
        maybeExportEvidence("junit5-extension", reportsRoot, jsonFiles, context.reportEntries)
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

    /** Reflective target for [LiveRecordingExtensionContext.getTestMethod]. */
    @Suppress("unused") fun intentionalFailJ5Marker() {}

    private suspend fun settleFixture() {
        with(fixture.automator) {
            refreshWindows()
            waitForIdle()
        }
        assertTrue(fixture.automator.surfaceIds().isNotEmpty(), "fixture must track a window")
    }

    private fun assertArtifactsOnDisk(reportsRoot: Path): List<Path> {
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
        return jsonFiles
    }

    /**
     * When evidence export is requested, copy the artifact tree + a manifest for verification
     * packaging. No-op during normal CI runs.
     *
     * Resolve order:
     * 1. System property `spectre.205.evidenceDir` (if the Test task forwards it)
     * 2. Environment variable `SPECTRE_205_EVIDENCE_DIR` (inherited by forked test JVMs)
     */
    private fun maybeExportEvidence(
        label: String,
        reportsRoot: Path,
        jsonFiles: List<Path>,
        reportEntries: List<Map<String, String>>,
    ) {
        val evidenceRoot =
            (System.getProperty("spectre.205.evidenceDir")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("SPECTRE_205_EVIDENCE_DIR")?.takeIf { it.isNotBlank() })
                ?.let { Path.of(it) } ?: return
        val out = evidenceRoot.resolve(label)
        Files.createDirectories(out)
        // Copy whole reports tree
        Files.walk(reportsRoot).use { stream ->
            stream.forEach { src ->
                val rel = reportsRoot.relativize(src)
                val dest = out.resolve("reports").resolve(rel.toString())
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest)
                } else if (Files.isRegularFile(src)) {
                    Files.createDirectories(dest.parent)
                    Files.copy(src, dest)
                }
            }
        }
        val firstJson = jsonFiles.first()
        val firstPng = firstJson.parent.resolve(CaptureArtifactsWriter.SCREENSHOT_PNG_NAME)
        val jsonText = Files.readString(firstJson)
        val excerpt =
            if (jsonText.length <= EVIDENCE_EXCERPT_CHARS) jsonText
            else jsonText.take(EVIDENCE_EXCERPT_CHARS) + "\n… [truncated]"
        val tree =
            Files.walk(out.resolve("reports")).use { stream ->
                stream.map { out.resolve("reports").relativize(it).toString() }.sorted().toList()
            }
        out.resolve("MANIFEST.txt")
            .writeText(
                buildString {
                    appendLine("label=$label")
                    appendLine("reportsRoot=$reportsRoot")
                    appendLine("capture.json=$firstJson size=${Files.size(firstJson)}")
                    appendLine("screenshot.png=$firstPng size=${Files.size(firstPng)}")
                    appendLine("reportEntries=$reportEntries")
                    appendLine("tree:")
                    tree.forEach { appendLine("  $it") }
                    appendLine("--- capture.json excerpt ---")
                    appendLine(excerpt)
                }
            )
    }

    private companion object {
        const val EVIDENCE_EXCERPT_CHARS: Int = 2_000
        /**
         * Mirrors internal FailureArtifactHooks.REPORT_ENTRY_KEY (public report-entry contract).
         */
        const val JUNIT5_FAILURE_ARTIFACT_REPORT_KEY: String = "spectre.failureArtifact"
    }
}

/**
 * Minimal recording [ExtensionContext] for live validation of
 * [ComposeAutomatorExtension.afterTestExecution].
 */
internal class LiveRecordingExtensionContext(
    private val failure: Throwable?,
    private val testClass: Class<*>,
    private val methodName: String,
    val reportEntries: MutableList<Map<String, String>> = mutableListOf(),
) : ExtensionContext {

    private val stores = ConcurrentHashMap<ExtensionContext.Namespace, ExtensionContext.Store>()
    private val uniqueId = "[engine:junit-jupiter]/class:${testClass.name}][method:$methodName()]"

    override fun getParent(): Optional<ExtensionContext> = Optional.empty()

    override fun getRoot(): ExtensionContext = this

    override fun getUniqueId(): String = uniqueId

    override fun getDisplayName(): String = methodName

    override fun getTags(): Set<String> = emptySet()

    override fun getElement(): Optional<AnnotatedElement> = Optional.empty()

    override fun getTestClass(): Optional<Class<*>> = Optional.of(testClass)

    override fun getTestInstanceLifecycle(): Optional<TestInstance.Lifecycle> =
        Optional.of(TestInstance.Lifecycle.PER_CLASS)

    override fun getTestInstance(): Optional<Any> = Optional.empty()

    override fun getTestInstances(): Optional<TestInstances> = Optional.empty()

    override fun getTestMethod(): Optional<Method> =
        Optional.of(testClass.getDeclaredMethod(methodName))

    override fun getExecutionException(): Optional<Throwable> = Optional.ofNullable(failure)

    override fun getConfigurationParameter(key: String): Optional<String> = Optional.empty()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getConfigurationParameter(
        key: String,
        transformer: Function<String, T>,
    ): Optional<T> = Optional.empty<Any>() as Optional<T>

    override fun publishReportEntry(map: Map<String, String>) {
        reportEntries += map
    }

    override fun getStore(namespace: ExtensionContext.Namespace): ExtensionContext.Store =
        stores.computeIfAbsent(namespace) { MapBackedStore() }

    override fun getExecutionMode(): ExecutionMode = ExecutionMode.SAME_THREAD

    override fun getExecutableInvoker(): ExecutableInvoker =
        object : ExecutableInvoker {
            override fun invoke(method: Method, target: Any?): Any =
                throw UnsupportedOperationException("not used in failure-artifact validation")

            override fun <T : Any?> invoke(
                constructor: java.lang.reflect.Constructor<T>,
                outerInstance: Any?,
            ): T = throw UnsupportedOperationException("not used in failure-artifact validation")
        }
}

private class MapBackedStore : ExtensionContext.Store {
    private val map = ConcurrentHashMap<Any, Any?>()

    override fun get(key: Any): Any? = map[key]

    override fun <V : Any?> get(key: Any, requiredType: Class<V>): V? {
        val value = map[key] ?: return null
        return requiredType.cast(value)
    }

    override fun <K : Any?, V : Any?> getOrComputeIfAbsent(
        key: K,
        defaultCreator: Function<K, V>,
    ): Any? {
        @Suppress("UNCHECKED_CAST")
        return map.computeIfAbsent(key as Any) { defaultCreator.apply(key) }
    }

    override fun <K : Any?, V : Any?> getOrComputeIfAbsent(
        key: K,
        defaultCreator: Function<K, V>,
        requiredType: Class<V>,
    ): V = requiredType.cast(getOrComputeIfAbsent(key, defaultCreator))

    override fun put(key: Any, value: Any?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun remove(key: Any): Any? = map.remove(key)

    override fun <V : Any?> remove(key: Any, requiredType: Class<V>): V? {
        val value = map.remove(key) ?: return null
        return requiredType.cast(value)
    }
}

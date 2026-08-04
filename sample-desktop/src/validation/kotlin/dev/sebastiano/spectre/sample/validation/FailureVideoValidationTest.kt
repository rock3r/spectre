package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.testing.ComposeAutomatorExtension
import dev.sebastiano.spectre.testing.ComposeAutomatorRule
import dev.sebastiano.spectre.testing.FailureArtifactsConfig
import dev.sebastiano.spectre.testing.FailureVideoConfig
import dev.sebastiano.spectre.testing.FailureVideoPolicy
import java.awt.GraphicsEnvironment
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 * Live acceptance for failure-video (#206) on a headed display with real
 * [dev.sebastiano.spectre.recording.AutoRecorder] backends.
 *
 * Skips cleanly when headless or when the platform recorder cannot start (TCC / missing helper) —
 * unit tests cover policy/lifecycle with a fake starter.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailureVideoValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre failure-video validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `JUnit 4 onFailureKeep keeps playable video on fail and deletes on pass`(
        @TempDir reportsRoot: Path
    ): Unit = runBlocking {
        settleFixture()
        val failRoot = reportsRoot.resolve("fail")
        Files.createDirectories(failRoot)
        val failRule =
            ComposeAutomatorRule(
                factory = { fixture.automator },
                failureArtifacts = FailureArtifactsConfig(enabled = false, reportsRoot = failRoot),
                failureVideo =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.OnFailureKeep,
                        reportsRoot = failRoot,
                        invocationId = "validation-fail",
                    ),
            )
        val failStatement =
            failRule.apply(
                object : Statement() {
                    override fun evaluate() {
                        // Hold the recording open long enough for backends to write frames.
                        Thread.sleep(1_200)
                        error("intentional failure for failure-video")
                    }
                },
                Description.createTestDescription(
                    FailureVideoValidationTest::class.java.name,
                    "intentionalFailVideoJ4",
                ),
            )
        try {
            failStatement.evaluate()
            error("expected intentional failure")
        } catch (expected: IllegalStateException) {
            assertEquals("intentional failure for failure-video", expected.message)
        }
        val kept = findVideos(failRoot)
        assumeTrue(
            kept.isNotEmpty(),
            "recorder did not produce a file (TCC / helper / platform backend unavailable)",
        )
        assertEquals(1, kept.size)
        assertTrue(Files.size(kept.single()) > 0, "kept video must be non-empty: ${kept.single()}")

        val passRoot = reportsRoot.resolve("pass")
        Files.createDirectories(passRoot)
        val passRule =
            ComposeAutomatorRule(
                factory = { fixture.automator },
                failureArtifacts = FailureArtifactsConfig(enabled = false, reportsRoot = passRoot),
                failureVideo =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.OnFailureKeep,
                        reportsRoot = passRoot,
                        invocationId = "validation-pass",
                    ),
            )
        passRule
            .apply(
                object : Statement() {
                    override fun evaluate() {
                        Thread.sleep(400)
                    }
                },
                Description.createTestDescription(
                    FailureVideoValidationTest::class.java.name,
                    "intentionalPassVideoJ4",
                ),
            )
            .evaluate()
        assertTrue(
            findVideos(passRoot).isEmpty(),
            "onFailureKeep must delete video on pass under $passRoot",
        )
    }

    @Test
    fun `JUnit 5 Always keeps video on pass`(@TempDir reportsRoot: Path): Unit = runBlocking {
        settleFixture()
        val extension =
            ComposeAutomatorExtension(
                factory = { fixture.automator },
                failureArtifacts =
                    FailureArtifactsConfig(enabled = false, reportsRoot = reportsRoot),
                failureVideo =
                    FailureVideoConfig(
                        policy = FailureVideoPolicy.Always,
                        reportsRoot = reportsRoot,
                        invocationId = "validation-always",
                    ),
            )
        val context =
            VideoRecordingExtensionContext(
                failure = null,
                testClass = FailureVideoValidationTest::class.java,
                methodName = "alwaysPassMarker",
            )
        extension.beforeEach(context)
        Thread.sleep(1_200)
        extension.afterEach(context)
        val kept = findVideos(reportsRoot)
        assumeTrue(
            kept.isNotEmpty(),
            "recorder did not produce a file (TCC / helper / platform backend unavailable)",
        )
        assertTrue(Files.size(kept.single()) > 0)
    }

    @Test
    fun `Off policy leaves no video even on failure`(@TempDir reportsRoot: Path): Unit =
        runBlocking {
            settleFixture()
            val rule =
                ComposeAutomatorRule(
                    factory = { fixture.automator },
                    failureArtifacts =
                        FailureArtifactsConfig(enabled = false, reportsRoot = reportsRoot),
                    failureVideo =
                        FailureVideoConfig(
                            policy = FailureVideoPolicy.Off,
                            reportsRoot = reportsRoot,
                        ),
                )
            val statement =
                rule.apply(
                    object : Statement() {
                        override fun evaluate() {
                            error("fail with video off")
                        }
                    },
                    Description.createTestDescription(
                        FailureVideoValidationTest::class.java.name,
                        "offPolicyFail",
                    ),
                )
            try {
                statement.evaluate()
            } catch (_: IllegalStateException) {
                // expected
            }
            assertFalse(
                Files.walk(reportsRoot).use { s -> s.anyMatch { Files.isRegularFile(it) } },
                "Off must not write under $reportsRoot",
            )
        }

    @Suppress("unused") fun alwaysPassMarker() {}

    private suspend fun settleFixture() {
        with(fixture.automator) {
            refreshWindows()
            waitForIdle()
        }
        assertTrue(fixture.automator.surfaceIds().isNotEmpty(), "fixture must track a window")
    }

    private fun findVideos(root: Path): List<Path> {
        if (!Files.exists(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream
                .filter { path ->
                    path.isRegularFile() && path.fileName.toString() == "failure-video.mp4"
                }
                .toList()
        }
    }
}

/** Minimal [ExtensionContext] for live validation of failure-video lifecycle. */
internal class VideoRecordingExtensionContext(
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
        stores.computeIfAbsent(namespace) { MapBackedVideoStore() }

    override fun getExecutionMode(): ExecutionMode = ExecutionMode.SAME_THREAD

    override fun getExecutableInvoker(): ExecutableInvoker =
        object : ExecutableInvoker {
            override fun invoke(method: Method, target: Any?): Any =
                throw UnsupportedOperationException("not used")

            override fun <T : Any?> invoke(
                constructor: java.lang.reflect.Constructor<T>,
                outerInstance: Any?,
            ): T = throw UnsupportedOperationException("not used")
        }
}

private class MapBackedVideoStore : ExtensionContext.Store {
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

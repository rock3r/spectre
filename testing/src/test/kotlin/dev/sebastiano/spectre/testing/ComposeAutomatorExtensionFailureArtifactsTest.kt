package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExecutableInvoker
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstances
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ExecutionMode
import org.opentest4j.TestAbortedException

/**
 * Drives the real [ComposeAutomatorExtension] failure-artifact callbacks
 * ([AfterTestExecutionCallback], lifecycle handlers) via a recording [ExtensionContext].
 *
 * Live capture.json + `spectre.failureArtifact` report entries (with real windows) are covered by
 * sample-desktop `FailureArtifactsValidationTest`, which also calls `afterTestExecution` on the
 * production extension with a live SampleAppFixture automator.
 */
class ComposeAutomatorExtensionFailureArtifactsTest {

    @Test
    fun `afterTestExecution sets once-per-invocation flag so lifecycle handler is a no-op`(
        @TempDir temp: Path
    ) {
        val extension = headlessExtension(temp)
        val context =
            RecordingExtensionContext(
                failure = AssertionError("boom"),
                testClass = Sample::class.java,
                methodName = "fails",
            )

        extension.beforeEach(context)
        extension.afterTestExecution(context)
        assertEquals(true, context.storeValue("failureArtifactsCaptured"))

        // A later @AfterEach failure re-enters captureFailureArtifacts; CAPTURED_KEY must short-
        // circuit before another recordFailure (would still rethrow the AfterEach throwable).
        try {
            extension.handleAfterEachMethodExecutionException(
                context,
                IllegalStateException("afterEach also failed"),
            )
            error("expected rethrow")
        } catch (expected: IllegalStateException) {
            assertEquals("afterEach also failed", expected.message)
        }
        assertEquals(true, context.storeValue("failureArtifactsCaptured"))
        // Headless: zero windows → nothing on disk either way; flag is the control-plane proof.
        assertFalse(Files.walk(temp).use { s -> s.anyMatch { Files.isRegularFile(it) } })
    }

    @Test
    fun `afterTestExecution skips TestAbortedException`(@TempDir temp: Path) {
        val extension = headlessExtension(temp)
        val context =
            RecordingExtensionContext(
                failure = TestAbortedException("assumption"),
                testClass = Sample::class.java,
                methodName = "assumed",
            )
        extension.beforeEach(context)
        extension.afterTestExecution(context)
        assertEquals(null, context.storeValue("failureArtifactsCaptured"))
        assertTrue(context.reportEntries.isEmpty())
    }

    @Test
    fun `lifecycle handler skips TestAbortedException`(@TempDir temp: Path) {
        val extension = headlessExtension(temp)
        val context =
            RecordingExtensionContext(
                failure = null,
                testClass = Sample::class.java,
                methodName = "beforeAssumed",
            )
        extension.beforeEach(context)
        try {
            extension.handleBeforeEachMethodExecutionException(
                context,
                TestAbortedException("skip before"),
            )
            error("expected rethrow")
        } catch (_: TestAbortedException) {
            // expected
        }
        assertEquals(null, context.storeValue("failureArtifactsCaptured"))
        assertTrue(context.reportEntries.isEmpty())
    }

    @Test
    fun `opt-out writes no report files`(@TempDir temp: Path) {
        val extension =
            ComposeAutomatorExtension(
                factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
                failureArtifacts = FailureArtifactsConfig(enabled = false, reportsRoot = temp),
            )
        val context =
            RecordingExtensionContext(
                failure = AssertionError("fail"),
                testClass = Sample::class.java,
                methodName = "optOut",
            )
        extension.beforeEach(context)
        extension.afterTestExecution(context)
        assertFalse(Files.walk(temp).use { s -> s.anyMatch { Files.isRegularFile(it) } })
        assertTrue(context.reportEntries.isEmpty())
    }

    private fun headlessExtension(temp: Path): ComposeAutomatorExtension =
        ComposeAutomatorExtension(
            factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
            failureArtifacts = FailureArtifactsConfig(reportsRoot = temp),
        )

    /**
     * Reflective targets for [ExtensionContext.getTestMethod]. Bodies touch [System.nanoTime] so
     * detekt does not flag EmptyFunctionBlock / FunctionOnlyReturningConstant.
     */
    class Sample {
        fun fails(): Long = System.nanoTime()

        fun assumed(): Long = System.nanoTime()

        fun beforeAssumed(): Long = System.nanoTime()

        fun optOut(): Long = System.nanoTime()
    }
}

/**
 * Minimal recording [ExtensionContext] that supports the store + report-entry + failure surface
 * used by [ComposeAutomatorExtension] failure-artifact hooks.
 */
internal class RecordingExtensionContext(
    private val failure: Throwable?,
    private val testClass: Class<*>,
    private val methodName: String,
    val reportEntries: MutableList<Map<String, String>> = mutableListOf(),
    private val uniqueId: String =
        "[engine:junit-jupiter]/class:${testClass.name}][method:$methodName()]",
) : ExtensionContext {

    private val stores = ConcurrentHashMap<ExtensionContext.Namespace, ExtensionContext.Store>()

    /** Scan stores for [key] — Namespace is private on the extension, so we cannot look it up. */
    fun storeValue(key: String): Any? {
        for (store in stores.values) {
            val value = store.get(key)
            if (value != null) return value
        }
        return null
    }

    override fun getParent(): Optional<ExtensionContext> = Optional.empty()

    override fun getRoot(): ExtensionContext = this

    override fun getUniqueId(): String = uniqueId

    override fun getDisplayName(): String = methodName

    override fun getTags(): Set<String> = emptySet()

    override fun getElement(): Optional<AnnotatedElement> = Optional.empty()

    override fun getTestClass(): Optional<Class<*>> = Optional.of(testClass)

    override fun getTestInstanceLifecycle(): Optional<TestInstance.Lifecycle> =
        Optional.of(TestInstance.Lifecycle.PER_METHOD)

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
                throw UnsupportedOperationException("not used in failure-artifact tests")

            override fun <T : Any?> invoke(
                constructor: java.lang.reflect.Constructor<T>,
                outerInstance: Any?,
            ): T = throw UnsupportedOperationException("not used in failure-artifact tests")
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

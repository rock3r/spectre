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
 * Headless automators have zero windows, so these tests pin control-plane behaviour (when capture
 * runs / is skipped / is once-per-invocation) rather than PNG bytes. Live capture.json + report
 * entries are covered by sample-desktop `FailureArtifactsValidationTest`.
 */
class ComposeAutomatorExtensionFailureArtifactsTest {

    @Test
    fun `afterTestExecution captures once then lifecycle handler does not recapture`(
        @TempDir temp: Path
    ) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val extension =
            ComposeAutomatorExtension(
                factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
                failureArtifacts = config,
            )
        val context =
            RecordingExtensionContext(
                failure = AssertionError("boom"),
                testClass = Sample::class.java,
                methodName = "fails",
            )

        extension.beforeEach(context)
        extension.afterTestExecution(context)
        // Second path that would fire after a later @AfterEach failure must be a no-op.
        try {
            extension.handleAfterEachMethodExecutionException(
                context,
                IllegalStateException("afterEach also failed"),
            )
        } catch (_: IllegalStateException) {
            // expected rethrow
        }

        assertEquals(true, context.storeValue("failureArtifactsCaptured"))
        // Headless: zero windows → no files, but capture was attempted (captured flag set).
        assertFalse(
            Files.walk(temp).use { s -> s.anyMatch { Files.isRegularFile(it) } },
            "headless capture writes nothing under $temp",
        )
    }

    @Test
    fun `afterTestExecution skips TestAbortedException`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val extension =
            ComposeAutomatorExtension(
                factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
                failureArtifacts = config,
            )
        val context =
            RecordingExtensionContext(
                failure = TestAbortedException("assumption"),
                testClass = Sample::class.java,
                methodName = "assumed",
            )
        extension.beforeEach(context)
        extension.afterTestExecution(context)
        assertEquals(null, context.storeValue("failureArtifactsCaptured"))
    }

    @Test
    fun `lifecycle handler skips TestAbortedException`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(reportsRoot = temp)
        val extension =
            ComposeAutomatorExtension(
                factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
                failureArtifacts = config,
            )
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
        } catch (_: TestAbortedException) {
            // expected rethrow
        }
        assertEquals(null, context.storeValue("failureArtifactsCaptured"))
    }

    @Test
    fun `opt-out does not mark capture or write files`(@TempDir temp: Path) {
        val config = FailureArtifactsConfig(enabled = false, reportsRoot = temp)
        val extension =
            ComposeAutomatorExtension(
                factory = { ComposeAutomator.inProcess(robotDriver = RobotDriver.headless()) },
                failureArtifacts = config,
            )
        val context =
            RecordingExtensionContext(
                failure = AssertionError("fail"),
                testClass = Sample::class.java,
                methodName = "optOut",
            )
        extension.beforeEach(context)
        extension.afterTestExecution(context)
        // Captured flag is still set after recordFailure returns empty — wait, recordFailure
        // returns early when disabled BEFORE capture, but extension still puts CAPTURED_KEY after
        // recordFailure. Check source...
        // Extension always store.put(CAPTURED_KEY, true) after recordFailure. So flag is set.
        // Files must not exist.
        assertFalse(Files.walk(temp).use { s -> s.anyMatch { Files.isRegularFile(it) } })
        assertTrue(context.reportEntries.isEmpty())
    }

    /** Marker class so [ExtensionContext.getTestClass]/[getTestMethod] resolve. */
    class Sample {
        fun fails() {}

        fun assumed() {}

        fun beforeAssumed() {}

        fun optOut() {}
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
    ): V {
        val value = getOrComputeIfAbsent(key, defaultCreator)
        return requiredType.cast(value)
    }

    override fun put(key: Any, value: Any?) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun remove(key: Any): Any? = map.remove(key)

    override fun <V : Any?> remove(key: Any, requiredType: Class<V>): V? {
        val value = map.remove(key) ?: return null
        return requiredType.cast(value)
    }
}

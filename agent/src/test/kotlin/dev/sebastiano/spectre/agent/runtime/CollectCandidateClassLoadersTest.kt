package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.core.ComposeAutomator
import java.lang.instrument.Instrumentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [collectCandidateClassLoaders], which is the thin filter over
 * [Instrumentation.getAllLoadedClasses] that picks out classes by FQN and collects their distinct
 * loaders.
 *
 * The test JVM's classpath already has Spectre `:core` (via the test dependency), so
 * `ComposeAutomator::class.java` is a real, loaded `Class<*>` we can feed into the stub
 * [Instrumentation]. The disambiguation rule itself is covered separately in [AgentBootstrapTest].
 */
class CollectCandidateClassLoadersTest {
    @Test
    fun `returns the ClassLoader of the matching class`() {
        val automatorClass = ComposeAutomator::class.java
        val instrumentation =
            StubInstrumentation(loadedClasses = arrayOf(automatorClass, String::class.java))

        val result = collectCandidateClassLoaders(instrumentation, COMPOSE_AUTOMATOR_FQN)

        assertEquals(1, result.size)
        assertSame(automatorClass.classLoader, result.single())
    }

    @Test
    fun `returns empty list when target FQN is not loaded`() {
        val instrumentation =
            StubInstrumentation(loadedClasses = arrayOf(String::class.java, Int::class.java))

        val result =
            collectCandidateClassLoaders(instrumentation, "no.such.Class.IsLoaded.HereOrAnywhere")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `deduplicates loaders when multiple matching classes share one ClassLoader`() {
        val automatorClass = ComposeAutomator::class.java
        // Pretend the same class shows up twice in `getAllLoadedClasses` (the JVM doesn't
        // do this in practice but the dedupe step still guards against it).
        val instrumentation =
            StubInstrumentation(
                loadedClasses = arrayOf(automatorClass, automatorClass, String::class.java)
            )

        val result = collectCandidateClassLoaders(instrumentation, COMPOSE_AUTOMATOR_FQN)

        assertEquals(1, result.size)
        assertSame(automatorClass.classLoader, result.single())
    }

    @Test
    fun `findSpectreClassLoader returns the loader from the initial getAllLoadedClasses scan`() {
        val automatorClass = ComposeAutomator::class.java
        val instrumentation =
            StubInstrumentation(loadedClasses = arrayOf(automatorClass, String::class.java))

        val result = AgentBootstrap.findSpectreClassLoader(instrumentation)

        assertSame(automatorClass.classLoader, result)
    }

    @Test
    fun `findSpectreClassLoader force-loads via system ClassLoader when getAllLoadedClasses is empty`() {
        // The real JVM the test runs in has ComposeAutomator on its classpath (via
        // `testImplementation(projects.core)`), but our stub deliberately reports zero
        // loaded classes. AgentBootstrap's fallback path force-loads `ComposeAutomator`
        // through the system loader and uses whatever loader resolved it.
        val instrumentation = StubInstrumentation(loadedClasses = emptyArray())

        val result = AgentBootstrap.findSpectreClassLoader(instrumentation)

        // The returned loader must be able to resolve `ComposeAutomator` itself — that's
        // the only invariant the agent depends on. The exact loader instance can vary
        // depending on how Gradle's test runner sets up the classpath (system vs. an
        // app classloader), so we assert behaviour rather than identity.
        assertEquals(COMPOSE_AUTOMATOR_FQN, result.loadClass(COMPOSE_AUTOMATOR_FQN).name)
    }
}

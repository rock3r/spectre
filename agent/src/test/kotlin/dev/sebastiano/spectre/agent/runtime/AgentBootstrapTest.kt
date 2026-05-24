package dev.sebastiano.spectre.agent.runtime

import com.intellij.ide.plugins.cl.PluginClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the classloader-disambiguation rule from D-14 in
 * `.plans/2026-05-22-issue-153-agent-attach-workshop.md`.
 *
 * The rule (see [selectClassLoader] kdoc):
 * 1. Empty candidates → [SpectreNotOnClasspathException]
 * 2. Single candidate → return it
 * 3. Multiple candidates, exactly one with `PluginClassLoader` in hierarchy → that one
 * 4. Multiple candidates with 0 or 2+ plugin-loader matches → [AmbiguousSpectreClasspathException]
 *
 * Tests are kept against the pure selection function [selectClassLoader] rather than the full
 * [AgentBootstrap.findSpectreClassLoader] because fabricating multiple distinct `Class<*>`
 * instances with the same FQN but different `ClassLoader`s requires gymnastics (`URLClassLoader`
 * plus a known JAR) that obscures the test intent. The Instrumentation-filtering step
 * (`collectCandidateClassLoaders`) is a trivial filter/distinct over the JVM-supplied class list —
 * covered by the M-1 spike's end-to-end verification against sample-desktop.
 */
class AgentBootstrapTest {
    @Test
    fun `selectClassLoader returns the only candidate when there is one`() {
        val loader = NamedClassLoader("solo")

        val result = selectClassLoader(listOf(loader))

        assertSame(loader, result)
    }

    @Test
    fun `selectClassLoader throws SpectreNotOnClasspathException when candidates is empty`() {
        assertFailsWith<SpectreNotOnClasspathException> { selectClassLoader(emptyList()) }
    }

    @Test
    fun `selectClassLoader prefers a candidate that reaches PluginClassLoader directly`() {
        val systemLoader = NamedClassLoader("system")
        val pluginLoader = PluginClassLoader()

        val result = selectClassLoader(listOf(systemLoader, pluginLoader))

        assertSame(pluginLoader, result)
    }

    @Test
    fun `selectClassLoader walks the parent chain to find PluginClassLoader`() {
        val pluginLoader = PluginClassLoader()
        val childOfPlugin = NamedClassLoader("plugin-child", parent = pluginLoader)
        val systemLoader = NamedClassLoader("system")

        val result = selectClassLoader(listOf(systemLoader, childOfPlugin))

        assertSame(childOfPlugin, result)
    }

    @Test
    fun `selectClassLoader throws AmbiguousSpectreClasspathException when no plugin loader is present`() {
        val systemLoader = NamedClassLoader("system")
        val customLoader = NamedClassLoader("custom")

        val ex =
            assertFailsWith<AmbiguousSpectreClasspathException> {
                selectClassLoader(listOf(systemLoader, customLoader))
            }
        assertEquals(listOf(systemLoader, customLoader), ex.candidates)
    }

    @Test
    fun `selectClassLoader throws AmbiguousSpectreClasspathException when multiple plugin loaders match`() {
        val pluginA = PluginClassLoader()
        val pluginB = PluginClassLoader()

        val ex =
            assertFailsWith<AmbiguousSpectreClasspathException> {
                selectClassLoader(listOf(pluginA, pluginB))
            }
        assertEquals(listOf(pluginA, pluginB), ex.candidates)
    }

    @Test
    fun `hasPluginClassLoaderInHierarchy detects the loader itself`() {
        val pluginLoader = PluginClassLoader()
        assertTrue(pluginLoader.hasPluginClassLoaderInHierarchy())
    }

    @Test
    fun `hasPluginClassLoaderInHierarchy returns false for an ordinary loader`() {
        val ordinaryLoader = NamedClassLoader("ordinary")
        assertEquals(false, ordinaryLoader.hasPluginClassLoaderInHierarchy())
    }

    @Test
    fun `hasPluginClassLoaderInHierarchy walks the parent chain`() {
        val plugin = PluginClassLoader()
        val middle = NamedClassLoader("middle", parent = plugin)
        val child = NamedClassLoader("child", parent = middle)

        assertTrue(child.hasPluginClassLoaderInHierarchy())
    }
}

/**
 * Minimal [ClassLoader] for tests that just needs to participate in the disambiguation rule's
 * identity comparisons and parent-chain walking. Carries a [displayName] so test failures include a
 * readable identifier.
 *
 * Property is [displayName] rather than `name` because `ClassLoader.getName()` exists in JDK 9+ and
 * a `val name` field collides with it at the JVM signature level.
 */
private class NamedClassLoader(val displayName: String, parent: ClassLoader? = null) :
    ClassLoader(parent) {
    override fun toString(): String = "NamedClassLoader($displayName)"
}

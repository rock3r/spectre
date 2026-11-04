package dev.sebastiano.spectre.agent.runtime

import com.intellij.ide.plugins.cl.PluginClassLoader
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #574: in IntelliJ, Compose lives in a plugin module classloader that the system classloader
 * cannot see, and before a Compose surface opens no Compose class is loaded at all.
 *
 * The tests build IntelliJ-shaped loaders over the jars or directories that hold the classes they
 * need, found from each class's code source. Each passes the platform classloader as the "system"
 * loader, because the real system loader here has Compose on its classpath and would hide the
 * plugin path.
 */
class ComposeHostDiscoveryTest {
    private val noComposeSystemLoader: ClassLoader = ClassLoader.getPlatformClassLoader()

    @Test
    fun `finds Compose through a plugin classloader before any Compose class is loaded`() {
        // The Compose library module: its loader exists, but has loaded no class yet.
        val composeModule =
            PluginClassLoader(
                codeSourcesOf(COMPOSE_HOST_MARKER_FQNS + KOTLIN_CLASS),
                noComposeSystemLoader,
            )
        // A plugin that depends on the Compose module and has already loaded its own classes.
        val plugin = PluginClassLoader(codeSourcesOf(listOf(FIXTURE_CLASS)), composeModule)
        val pluginClass = plugin.loadClass(FIXTURE_CLASS)
        assertSame(plugin, pluginClass.classLoader, "test setup: the plugin defines its own class")
        val instrumentation = StubInstrumentation(arrayOf(pluginClass, String::class.java))

        val host =
            AgentBootstrap.findComposeHostClassLoader(
                instrumentation,
                systemLoader = noComposeSystemLoader,
            )

        assertSame(composeModule, host, "Compose belongs to the loader that defines it")
    }

    @Test
    fun `ignores plugin classloaders that cannot see Compose`() {
        val plugin = PluginClassLoader(codeSourcesOf(listOf(KOTLIN_CLASS)), noComposeSystemLoader)
        val pluginClass = plugin.loadClass(KOTLIN_CLASS)
        assertSame(plugin, pluginClass.classLoader, "test setup: the plugin defines its own class")
        val instrumentation = StubInstrumentation(arrayOf(pluginClass))

        assertFailsWith<ComposeNotOnClasspathException> {
            AgentBootstrap.findComposeHostClassLoader(
                instrumentation,
                systemLoader = noComposeSystemLoader,
            )
        }
    }

    @Test
    fun `an IntelliJ Platform target can wait for Compose to load`() {
        val instrumentation =
            StubInstrumentation(arrayOf(PluginClassLoader::class.java, String::class.java))

        assertTrue(AgentBootstrap.canWaitForCompose(instrumentation))
    }

    @Test
    fun `a target without IntelliJ plugin loaders cannot wait for Compose to load`() {
        val instrumentation = StubInstrumentation(arrayOf(String::class.java))

        assertFalse(AgentBootstrap.canWaitForCompose(instrumentation))
    }

    /** The distinct jars or class directories that hold [classNames] in this test JVM. */
    private fun codeSourcesOf(classNames: List<String>): Array<URL> =
        classNames
            .map { name ->
                Class.forName(name, false, javaClass.classLoader)
                    .protectionDomain
                    .codeSource
                    .location
            }
            .distinct()
            .toTypedArray()

    private companion object {
        const val FIXTURE_CLASS = "dev.sebastiano.spectre.agent.fixture.LazyComposeFixtureMainKt"
        const val KOTLIN_CLASS = "kotlin.Unit"
    }
}

package dev.sebastiano.spectre.agent.fixture

import com.intellij.ide.plugins.cl.PluginClassLoader
import java.awt.Dimension
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * IntelliJ-shaped fixture for #574: Compose lives only in a plugin classloader, and nothing loads
 * it until a Compose surface opens.
 *
 * The JVM starts with a classpath that has no Compose on it (the test passes only this module and
 * the Kotlin standard library). At startup the fixture creates a [PluginClassLoader] over the
 * classpath named by [LAZY_FIXTURE_PLUGIN_CLASSPATH_PROPERTY], the way IntelliJ creates its plugin
 * loaders before any plugin runs, and shows a Swing-only window like IntelliJ's Welcome screen.
 * When the line [LAZY_FIXTURE_LOAD_COMPOSE_COMMAND] arrives on stdin, it runs the inject fixture UI
 * through that plugin loader. That UI prints [READY_SENTINEL] once its semantics tree is ready.
 *
 * This file must not reference Compose or `spectre-core`: they are not on this JVM's classpath.
 */
fun main() {
    val pluginClasspath =
        System.getProperty(LAZY_FIXTURE_PLUGIN_CLASSPATH_PROPERTY)
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.map { File(it).toURI().toURL() }
            ?: error("Missing -D$LAZY_FIXTURE_PLUGIN_CLASSPATH_PROPERTY")
    // Like IntelliJ's plugin loaders, this one shares the JVM's Kotlin standard library and
    // loads everything else, Compose included, from its own classpath.
    val composePlugin =
        PluginClassLoader(pluginClasspath.toTypedArray(), ClassLoader.getSystemClassLoader())

    SwingUtilities.invokeAndWait {
        JFrame(LAZY_FIXTURE_WELCOME_TITLE).apply {
            defaultCloseOperation = FIXTURE_DEFAULT_CLOSE_OPERATION
            size = Dimension(WELCOME_WIDTH_PX, WELCOME_HEIGHT_PX)
            isVisible = true
        }
    }
    println("$LAZY_FIXTURE_WELCOME_SENTINEL pid=${ProcessHandle.current().pid()}")
    System.out.flush()

    val stdin = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    while (true) {
        // A closed stdin keeps the fixture on its Swing-only window until the test destroys it,
        // like an IDE that never opens a Compose surface.
        val line = stdin.readLine() ?: return Thread.currentThread().join()
        if (line.trim() == LAZY_FIXTURE_LOAD_COMPOSE_COMMAND) break
    }

    // Runs the inject fixture entirely inside the plugin loader; it blocks forever once ready.
    composePlugin.loadClass(INJECT_FIXTURE_MAIN_CLASS).getMethod("injectMain").invoke(null)
}

/** System property naming the classpath of the fixture's Compose "plugin" loader. */
const val LAZY_FIXTURE_PLUGIN_CLASSPATH_PROPERTY: String = "spectre.fixture.pluginClasspath"

/** Printed once the Swing-only window is up and no Compose class has been loaded. */
const val LAZY_FIXTURE_WELCOME_SENTINEL: String = "SPECTRE-LAZY-FIXTURE-WELCOME"

/** Title of the Swing-only window shown before Compose loads. */
const val LAZY_FIXTURE_WELCOME_TITLE: String = "Spectre Lazy Fixture Welcome"

/** Stdin line that makes the fixture load Compose through its plugin loader. */
const val LAZY_FIXTURE_LOAD_COMPOSE_COMMAND: String = "load-compose"

private const val INJECT_FIXTURE_MAIN_CLASS =
    "dev.sebastiano.spectre.agent.fixture.InjectComposeFixtureMainKt"
private const val WELCOME_WIDTH_PX: Int = 240
private const val WELCOME_HEIGHT_PX: Int = 120

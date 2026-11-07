@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.fixture.LAZY_FIXTURE_LOAD_COMPOSE_COMMAND
import dev.sebastiano.spectre.agent.fixture.LAZY_FIXTURE_PLUGIN_CLASSPATH_PROPERTY
import dev.sebastiano.spectre.agent.fixture.LAZY_FIXTURE_WELCOME_SENTINEL
import dev.sebastiano.spectre.agent.fixture.SPECTRE_FIXTURE_WINDOW_TITLE
import dev.sebastiano.spectre.agent.fixture.TAG_BUTTON
import java.awt.GraphicsEnvironment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * #574: attaching to an IntelliJ-shaped target before any Compose UI has loaded.
 *
 * In IntelliJ, Compose lives in a plugin module classloader, and nothing loads it until a Compose
 * surface opens. The fixture reproduces that shape (see `LazyComposeFixtureMain.kt`): Compose is
 * not on its system classpath, and its plugin loader has loaded no class at attach time. Attach
 * must succeed, report no windows, and start serving the Compose UI once it appears.
 *
 * Same OS gate and opt-in as [AgentInjectAttachIntegrationTest].
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class AgentLazyComposeAttachIntegrationTest {

    @Test
    fun `attach before Compose loads succeeds and serves Compose once it appears`() {
        assumeTrue(WindowsAttachE2eGate.isAllowed(), "Windows attach UI e2e is opt-in")
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for Compose Desktop",
        )
        val agentJar = locateAgentJarOrSkip()

        spawnLazyFixture().use { fixture ->
            AgentAttach.attach(pid = fixture.pid, options = AttachOptions(agentJarPath = agentJar))
                .use { automator ->
                    assertEquals(emptyList(), automator.windows(), "no Compose UI is loaded yet")

                    fixture.loadCompose()
                    // The wait starts before Compose has loaded, so it has to keep looking
                    // until the plugin's Compose UI shows up.
                    automator.waitForNode(tag = TAG_BUTTON, timeoutMs = COMPOSE_LOAD_TIMEOUT_MS)

                    assertTrue(
                        automator.windows().any { it.title == SPECTRE_FIXTURE_WINDOW_TITLE },
                        "expected the plugin's Compose window once it loaded",
                    )
                    assertTrue(automator.allNodes().isNotEmpty())
                }
        }
    }

    private fun spawnLazyFixture(): LazyFixtureProcess {
        val javaExe =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
                "java.exe"
            else "java"
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", javaExe).toString()
        val testClasspath = System.getProperty("java.class.path").split(File.pathSeparator)
        // The plugin loader gets everything except spectre-core, so the agent has to inject.
        val pluginClasspath =
            testClasspath.filterNot(InjectClasspathStrip::isSpectreCoreClasspathEntry)
        // The JVM itself gets only the fixture and the Kotlin standard library: no Compose.
        val systemClasspath = testClasspath.filter(::isLazyFixtureLauncherEntry)
        assertTrue(
            systemClasspath.any { "/agent-test-fixture/" in normalize(it) },
            "expected :agent-test-fixture on the test classpath; sample=${testClasspath.take(8)}",
        )

        val process =
            ProcessBuilder(
                    javaBin,
                    "-cp",
                    systemClasspath.joinToString(File.pathSeparator),
                    "-D$LAZY_FIXTURE_PLUGIN_CLASSPATH_PROPERTY=" +
                        pluginClasspath.joinToString(File.pathSeparator),
                    "-XX:+EnableDynamicAgentLoading",
                    "-Djava.awt.headless=false",
                    "-Dcompose.application.configure.swing.globals=true",
                    "-Dapple.awt.UIElement=false",
                    "dev.sebastiano.spectre.agent.fixture.LazyComposeFixtureMainKt",
                )
                .redirectErrorStream(true)
                .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val welcomeLatch = CountDownLatch(1)
        val drainerThread =
            Thread({
                    try {
                        generateSequence(reader::readLine).forEach { line ->
                            if (line.startsWith(LAZY_FIXTURE_WELCOME_SENTINEL)) {
                                welcomeLatch.countDown()
                            }
                        }
                    } catch (_: java.io.IOException) {
                        // pipe closed
                    }
                })
                .apply {
                    isDaemon = true
                    name = "lazy-fixture-stdout-drainer"
                    start()
                }

        if (!welcomeLatch.await(FIXTURE_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error("Lazy fixture did not emit $LAZY_FIXTURE_WELCOME_SENTINEL in time")
        }
        return LazyFixtureProcess(process, reader, drainerThread)
    }

    private fun isLazyFixtureLauncherEntry(entry: String): Boolean {
        val normalized = normalize(entry)
        return "/agent-test-fixture/build/" in normalized ||
            normalized.substringAfterLast('/').startsWith("kotlin-stdlib")
    }

    private fun normalize(entry: String): String =
        InjectClasspathStrip.normalizeClasspathEntry(entry)

    private fun locateAgentJarOrSkip(): Path {
        val prop = System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
        assumeFalse(prop.isNullOrBlank(), "Requires -Ddev.sebastiano.spectre.agent.runtimeJar")
        val path = Paths.get(prop!!)
        assumeFalse(!Files.isRegularFile(path), "Agent runtime JAR not found at $path")
        return path
    }

    private class LazyFixtureProcess(
        private val process: Process,
        private val reader: BufferedReader,
        private val drainer: Thread,
    ) : AutoCloseable {
        val pid: Long = process.pid()

        fun loadCompose() {
            process.outputStream.write("$LAZY_FIXTURE_LOAD_COMPOSE_COMMAND\n".toByteArray())
            process.outputStream.flush()
        }

        override fun close() {
            process.destroyForcibly()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            runCatching { drainer.join(1_000) }
        }
    }

    private companion object {
        // GitHub's macOS runners can take more than 30 s to start the Compose fixture.
        const val FIXTURE_READY_TIMEOUT_MS: Long = 90_000
        const val COMPOSE_LOAD_TIMEOUT_MS: Long = 30_000
    }
}

@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.agent.fixture.SPECTRE_FIXTURE_WINDOW_TITLE
import dev.sebastiano.spectre.agent.fixture.TAG_BUTTON
import dev.sebastiano.spectre.agent.fixture.TAG_LABEL
import dev.sebastiano.spectre.agent.fixture.TAG_TEXT_FIELD
import java.awt.GraphicsEnvironment
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * #209 / #313: attach + tree dump against a Compose target that does **not** have spectre-core on
 * its classpath. The agent runtime injects relocated core from
 * `META-INF/spectre/inject-runtime.jar`.
 *
 * Drives the real attach/UDS path ([AgentAttach.attach]) — not a re-implementation.
 *
 * **OS gate:** Linux and macOS via `@EnabledOnOs` (same policy as [AgentAttachIntegrationTest]).
 * Hosted Windows CI has no reliable interactive desktop; physical Windows inject e2e was validated
 * on Mattone. Windows classpath shapes are covered by [InjectClasspathStripTest].
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class AgentInjectAttachIntegrationTest {

    @Test
    fun `inject attach dumps semantics tree without preinstalled spectre-core`() {
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for Compose Desktop",
        )
        val agentJar = locateAgentJarOrSkip()

        spawnInjectFixture().use { fixture ->
            AgentAttach.attach(pid = fixture.pid, options = AttachOptions(agentJarPath = agentJar))
                .use { automator ->
                    val windows = automator.windows()
                    assertTrue(
                        windows.any { it.title == SPECTRE_FIXTURE_WINDOW_TITLE },
                        "expected fixture window in $windows",
                    )
                    val nodes = automator.allNodes()
                    assertTrue(nodes.isNotEmpty(), "expected non-empty tree dump after inject")
                    assertTrue(automator.findByTestTag(TAG_LABEL).isNotEmpty())
                    assertTrue(automator.findByTestTag(TAG_BUTTON).isNotEmpty())
                    assertTrue(automator.findByTestTag(TAG_TEXT_FIELD).isNotEmpty())
                }
        }
    }

    private fun spawnInjectFixture(): FixtureProcess {
        val javaExe =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
                "java.exe"
            else "java"
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", javaExe).toString()
        val classpath = classpathWithoutSpectreCore()

        val process =
            ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    "-XX:+EnableDynamicAgentLoading",
                    "-Djava.awt.headless=false",
                    "-Dcompose.application.configure.swing.globals=true",
                    "-Dapple.awt.UIElement=false",
                    "dev.sebastiano.spectre.agent.fixture.InjectComposeFixtureMainKt",
                )
                .redirectErrorStream(true)
                .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val readyLatch = CountDownLatch(1)
        val drainerThread =
            Thread({
                    try {
                        generateSequence(reader::readLine).forEach { line ->
                            if (line.startsWith(READY_SENTINEL) && readyLatch.count > 0) {
                                readyLatch.countDown()
                            }
                        }
                    } catch (_: java.io.IOException) {
                        // pipe closed
                    }
                })
                .apply {
                    isDaemon = true
                    name = "inject-fixture-stdout-drainer"
                    start()
                }

        if (!readyLatch.await(FIXTURE_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error(
                "Inject fixture did not emit $READY_SENTINEL within ${FIXTURE_READY_TIMEOUT_MS} ms"
            )
        }

        return FixtureProcess(process, process.pid(), reader, drainerThread)
    }

    private fun locateAgentJarOrSkip(): Path {
        val prop = System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
        assumeFalse(prop.isNullOrBlank(), "Requires -Ddev.sebastiano.spectre.agent.runtimeJar")
        val path = Paths.get(prop!!)
        assumeFalse(!Files.isRegularFile(path), "Agent runtime JAR not found at $path")
        return path
    }

    private fun classpathWithoutSpectreCore(): String {
        val sep = java.io.File.pathSeparator
        val original = System.getProperty("java.class.path").split(sep)
        val coreEntries = original.filter { isSpectreCoreClasspathEntry(it) }
        // Prove the attacher JVM actually had :core to strip — otherwise the child might still
        // inherit core and forceLoadFromSystemLoader would hide a broken inject path.
        assertTrue(
            coreEntries.isNotEmpty(),
            "Expected spectre :core on the test classpath so inject e2e can strip it; " +
                "entries sample=${original.take(8)}",
        )
        val stripped = original.filterNot { isSpectreCoreClasspathEntry(it) }
        assertTrue(
            stripped.none { isSpectreCoreClasspathEntry(it) },
            "Strip left residual core entries: ${stripped.filter { isSpectreCoreClasspathEntry(it) }}",
        )
        assertTrue(
            stripped.size < original.size,
            "Classpath strip did not remove any entries (original size=${original.size})",
        )
        return stripped.joinToString(sep)
    }

    /**
     * Spectre `:core` module outputs only — never `kotlinx-coroutines-core` or other `*-core`
     * artifacts.
     *
     * Uses [java.io.File.invariantSeparatorsPath] so Windows `\` classpaths match the same
     * `/core/build/...` markers as POSIX (inject e2e on physical Windows).
     */
    private fun isSpectreCoreClasspathEntry(entry: String): Boolean =
        InjectClasspathStrip.isSpectreCoreClasspathEntry(entry)

    private class FixtureProcess(
        private val process: Process,
        val pid: Long,
        private val reader: BufferedReader,
        private val drainer: Thread,
    ) : AutoCloseable {
        override fun close() {
            process.destroyForcibly()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            runCatching { reader.close() }
            runCatching { drainer.join(1_000) }
        }
    }

    private companion object {
        const val FIXTURE_READY_TIMEOUT_MS: Long = 30_000
    }
}

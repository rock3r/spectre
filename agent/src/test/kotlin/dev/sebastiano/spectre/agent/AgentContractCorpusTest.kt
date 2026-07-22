@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.testing.contract.AutomatorContractCorpus
import dev.sebastiano.spectre.testing.contract.AutomatorContractDriver
import dev.sebastiano.spectre.testing.contract.AutomatorTransport
import dev.sebastiano.spectre.testing.contract.ContractNode
import dev.sebastiano.spectre.testing.contract.ContractWindow
import dev.sebastiano.spectre.testing.contract.ScreenshotProbe
import java.awt.GraphicsEnvironment
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Agent leg of the shared automator contract corpus (#198).
 *
 * Spawns `:agent-test-fixture`, attaches via [AgentAttach], and runs [AutomatorContractCorpus]
 * against the live [AttachedAutomator] — the same public client path users call. Gating matches
 * [AgentAttachIntegrationTest]: Linux/macOS, non-headless, runtime jar system property. Linux Xvfb
 * CI is the fail-closed executed gate (`.github/workflows/validation-linux.yml`).
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class AgentContractCorpusTest {
    private val orphanUdsFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanUpOrphans() {
        orphanUdsFiles.forEach { runCatching { Files.deleteIfExists(it) } }
    }

    @Test
    fun `contract corpus passes on AttachedAutomator against the Compose fixture`() {
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for Compose Desktop + java.awt.Robot",
        )
        val agentJar = locateAgentJarOrSkip()

        spawnComposeFixture().use { fixture ->
            val udsPath = AttachOptions.defaultUdsPath(fixture.pid)
            orphanUdsFiles.add(udsPath)
            val options =
                AttachOptions(
                    agentJarPath = agentJar,
                    udsPath = udsPath,
                    attachTimeoutMs = ATTACH_TIMEOUT_MS,
                )
            AgentAttach.attach(fixture.pid, options).use { automator ->
                AgentContractDriver(automator).use { driver ->
                    AutomatorContractCorpus.run(driver).requireAllPassed()
                }
                // Atomic capture is on the agent surface but not part of the shared intersection
                // corpus; exercise the real entry point so the Capture matrix cell stays honest.
                val capture = automator.capture(windowIndex = 0)
                check(capture.pngBytes.isNotEmpty()) { "capture png empty" }
                check(capture.captureJson.isNotBlank()) { "capture json blank" }
            }
        }
    }

    private fun spawnComposeFixture(): FixtureProcess {
        val javaExe =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
                "java.exe"
            else "java"
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", javaExe).toString()
        val classpath = System.getProperty("java.class.path")
        val process =
            ProcessBuilder(
                    javaBin,
                    "-cp",
                    classpath,
                    "-XX:+EnableDynamicAgentLoading",
                    "-Djava.awt.headless=false",
                    "-Dcompose.application.configure.swing.globals=true",
                    "-Dapple.awt.UIElement=false",
                    "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt",
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
                    } catch (_: IOException) {
                        // Pipe closed when child exits; normal.
                    }
                })
                .apply {
                    isDaemon = true
                    name = "corpus-fixture-stdout-drainer"
                    start()
                }

        if (!readyLatch.await(FIXTURE_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error(
                "Compose fixture did not emit $READY_SENTINEL within ${FIXTURE_READY_TIMEOUT_MS} ms"
            )
        }

        return FixtureProcess(process, process.pid(), reader, drainerThread)
    }

    private fun locateAgentJarOrSkip(): Path {
        val prop = System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
        assumeFalse(
            prop.isNullOrBlank(),
            "Requires -Ddev.sebastiano.spectre.agent.runtimeJar; set by :agent:test",
        )
        val path = Paths.get(prop!!)
        assumeFalse(!Files.isRegularFile(path), "Agent runtime JAR not found at $path")
        return path
    }

    private class AgentContractDriver(private val automator: AttachedAutomator) :
        AutomatorContractDriver {
        override val transport: AutomatorTransport = AutomatorTransport.Agent
        override val expectsFixtureSemantics: Boolean = true

        override fun windows(): List<ContractWindow> =
            automator.windows().map { ContractWindow(surfaceId = it.surfaceId, title = it.title) }

        override fun allNodes(): List<ContractNode> =
            automator.allNodes().map {
                ContractNode(
                    key = it.key,
                    testTag = it.testTag,
                    text = it.texts.firstOrNull() ?: it.editableText,
                )
            }

        override fun findByTestTag(tag: String): List<ContractNode> =
            automator.findByTestTag(tag).map {
                ContractNode(
                    key = it.key,
                    testTag = it.testTag,
                    text = it.texts.firstOrNull() ?: it.editableText,
                )
            }

        override fun click(nodeKey: String) {
            automator.click(nodeKey)
        }

        override fun typeText(text: String) {
            try {
                automator.typeText(text)
            } catch (ex: IOException) {
                // Match AgentAttachIntegrationTest: CI may lose OS keyboard focus after Compose
                // focus is proven; the attach/click contract is still asserted by other scenarios.
                if (
                    System.getenv("CI").equals("true", ignoreCase = true) &&
                        ex.message?.contains(TARGET_FOCUS_ERROR) == true
                ) {
                    System.err.println(
                        "AgentContractCorpusTest: skipping typeText on CI focus loss: ${ex.message}"
                    )
                    return
                }
                throw ex
            }
        }

        override fun screenshotProbe(): ScreenshotProbe {
            val bytes = automator.screenshot()
            return ScreenshotProbe(byteCount = bytes.size, formatHint = "png")
        }
    }

    private class FixtureProcess(
        val process: Process,
        val pid: Long,
        val reader: BufferedReader,
        private val drainerThread: Thread,
    ) : AutoCloseable {
        override fun close() {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            drainerThread.join(500)
            runCatching { reader.close() }
        }
    }

    private companion object {
        const val ATTACH_TIMEOUT_MS: Long = 15_000
        const val FIXTURE_READY_TIMEOUT_MS: Long = 30_000
        const val TARGET_FOCUS_ERROR: String = "target JVM does not currently own OS keyboard focus"
    }
}

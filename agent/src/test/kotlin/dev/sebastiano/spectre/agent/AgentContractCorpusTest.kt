@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    /**
     * #201 fixture cancel: interrupt a live [AttachedAutomator.waitForNode] against the Compose
     * fixture. Client interrupt cancels the remote op; taxonomy must be `cancelled` (not hang /
     * timeout / connection error). Complements infrastructure cancel tests that use a reflective
     * fake automator without a real Compose tree.
     */
    @Test
    fun `waitForNode on live fixture is cancelled when the client thread is interrupted`() {
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
                // Prove the fixture is live before starting a long wait.
                assertTrue(automator.windows().isNotEmpty(), "fixture should expose a window")

                val error = AtomicReference<Throwable?>(null)
                val started = CountDownLatch(1)
                val waiter =
                    Thread({
                            try {
                                started.countDown()
                                automator.waitForNode(
                                    tag = "agent-fixture-never-appears-cancel",
                                    timeoutMs = 25_000,
                                )
                                error.set(
                                    AssertionError("waitForNode was expected to be cancelled")
                                )
                            } catch (ex: Throwable) {
                                error.set(ex)
                            }
                        })
                        .apply {
                            isDaemon = true
                            name = "fixture-wait-cancel"
                            start()
                        }

                assertTrue(started.await(3, TimeUnit.SECONDS), "wait thread never started")
                // Let the WaitForNode op leave the client and enter the agent worker.
                Thread.sleep(200)
                waiter.interrupt()
                waiter.join(10_000)
                assertTrue(!waiter.isAlive, "wait thread still running after interrupt")

                val thrown = error.get()
                val agentEx =
                    assertIs<SpectreAgentException>(
                        thrown,
                        "expected SpectreAgentException, got $thrown",
                    )
                assertEquals(AgentErrorCategory.Cancelled, agentEx.category)

                // Session remains usable after cancel.
                assertTrue(automator.windows().isNotEmpty(), "windows() after cancel")
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

        override fun findByText(text: String, exact: Boolean): List<ContractNode> =
            automator.findByText(text, exact).map {
                ContractNode(
                    key = it.key,
                    testTag = it.testTag,
                    text = it.texts.firstOrNull() ?: it.editableText,
                )
            }

        override fun findByContentDescription(description: String): List<ContractNode> =
            automator.findByContentDescription(description).map {
                ContractNode(
                    key = it.key,
                    testTag = it.testTag,
                    text = it.texts.firstOrNull() ?: it.editableText,
                )
            }

        override fun findByRole(role: String): List<ContractNode> =
            automator.findByRole(role).map {
                ContractNode(
                    key = it.key,
                    testTag = it.testTag,
                    text = it.texts.firstOrNull() ?: it.editableText,
                )
            }

        override val supportsWaitTaxonomy: Boolean = true

        override fun waitForNode(tag: String?, text: String?, timeoutMs: Long): String =
            automator.waitForNode(tag = tag, text = text, timeoutMs = timeoutMs).key

        override fun waitForNodeFailureCategory(
            tag: String?,
            text: String?,
            timeoutMs: Long,
        ): String {
            try {
                automator.waitForNode(tag = tag, text = text, timeoutMs = timeoutMs)
                error("waitForNode was expected to time out")
            } catch (ex: SpectreAgentException) {
                return ex.category.wireName
            }
        }

        override fun click(nodeKey: String) {
            automator.click(nodeKey)
        }

        override fun typeText(text: String) {
            // Do not soft-succeed on focus loss: TypeText is Experimental on the agent matrix
            // precisely because CI focus flakes exist. AgentAttachIntegrationTest owns the
            // nuanced CI skip path; the shared corpus must not claim a silent pass.
            automator.typeText(text)
        }

        override fun doubleClick(nodeKey: String) {
            automator.doubleClick(nodeKey)
        }

        override fun swipe(fromNodeKey: String, toNodeKey: String) {
            automator.swipe(fromNodeKey, toNodeKey)
        }

        override fun scrollWheel(nodeKey: String, wheelClicks: Int) {
            automator.scrollWheel(nodeKey, wheelClicks)
        }

        override fun pressKey(keyCode: Int, modifiers: Int) {
            automator.pressKey(keyCode, modifiers)
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
    }
}

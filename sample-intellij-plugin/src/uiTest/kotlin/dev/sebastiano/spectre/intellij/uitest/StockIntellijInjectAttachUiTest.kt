@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.intellij.uitest

import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitForProjectOpen
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.junit5.hyphenateWithClass
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.CurrentTestMethod
import com.intellij.ide.starter.runner.IDERunContext
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimateProductInit
import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreProcesses
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * #353 / #376: stock IntelliJ inject attach e2e.
 *
 * Boots IDEA via ide-starter with the **no-core** sample plugin (Jewel tags only, no spectre-core),
 * enables dynamic agent loading, opens the Spectre Sample tool window, then attaches from this test
 * JVM with [AgentAttach] so bootstrap must inject nested `META-INF/spectre/inject-runtime.jar`.
 * Asserts proving tags and the inject agent log line.
 *
 * Opt-in: `./gradlew :sample-intellij-plugin:stockInjectUiTest` — not part of default `:check`
 * (always-on stock IDE inject CI remains a non-goal per #353).
 */
class StockIntellijInjectAttachUiTest {

    @Test
    @DisplayName("inject attach discovers Jewel tags on no-core sample plugin")
    fun injectAttachDiscoversJewelTagsOnNoCoreSamplePlugin() {
        assumeFalse(
            java.awt.GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for IDE-hosted Compose / attach e2e",
        )
        val pluginPath = noCorePluginZipPath()
        val agentJar = agentRuntimeJarPath()
        val tempProject = createEmptyProject()

        val testContext =
            Starter.newContext(
                    CurrentTestMethod.hyphenateWithClass(),
                    TestCase(
                        IdeaUltimateProductInit()
                            .ideInfo
                            .copy(buildType = "release", buildNumber = IDE_BUILD_NUMBER),
                        LocalProjectInfo(tempProject),
                    ),
                )
                .apply { PluginConfigurator(this).installPluginFromPath(pluginPath) }
                .applyVMOptionsPatch {
                    addSystemProperty("jetbrainsd.discovery.enabled", false)
                    addSystemProperty("jetbrainsd.uri.handling.enabled", false)
                    addSystemProperty("idea.trust.all.projects", true)
                    setIdeStartupDialogEnabled(false)
                    disableStartupDialogs()
                    disableNewUsersOnboardingDialogue()
                    // JEP 451 — required for dynamic agent attach on JDK 21+.
                    addLine("-XX:+EnableDynamicAgentLoading")
                }
                .skipIndicesInitialization()

        val capturedRunContext = AtomicReference<IDERunContext>()
        val backgroundRun =
            testContext.runIdeWithDriver(configure = { capturedRunContext.set(this) })

        backgroundRun.useDriverAndCloseIde {
            val runContext =
                requireNotNull(capturedRunContext.get()) {
                    "IDERunContext was never captured — runIdeWithDriver configure block did not fire."
                }
            val ideLog = runContext.logsDir.resolve("idea.log")

            waitForProjectOpen(timeout = PROJECT_OPEN_TIMEOUT)
            waitForIndicators(timeout = INDICATOR_QUIESCENCE_TIMEOUT)

            openToolWindow(TOOL_WINDOW_ID)

            val idePid = resolveIdeJvmPid(backgroundRun.process.id.toLong())
            AgentAttach.attach(pid = idePid, options = AttachOptions(agentJarPath = agentJar))
                .use { automator ->
                    // Composition is async after tool-window show; wait on the wire.
                    automator.waitForNode(tag = "ide.counter.text", timeoutMs = TAG_WAIT_TIMEOUT_MS)
                    assertTrue(automator.findByTestTag("ide.counter.button").isNotEmpty()) {
                        "expected ide.counter.button after inject; tags=" +
                            automator.allNodes().mapNotNull { it.testTag }
                    }
                    assertTrue(automator.findByTestTag("ide.popup.toggleButton").isNotEmpty()) {
                        "expected ide.popup.toggleButton after inject; tags=" +
                            automator.allNodes().mapNotNull { it.testTag }
                    }
                }

            // Prove inject path (not preinstalled core): agent logs to target System.err →
            // idea.log.
            val injectLogSeen = waitForInjectLog(ideLog, deadlineMs = INJECT_LOG_POLL_MS)
            assertTrue(injectLogSeen) {
                "expected inject bootstrap log in $ideLog (" +
                    "\"spectre-core not on target classpath; injecting\"). " +
                    (if (ideLog.exists()) "log tail:\n" + ideLog.readText().takeLast(LOG_TAIL_CHARS)
                    else "log missing")
            }
        }
    }

    private fun noCorePluginZipPath(): Path {
        val raw =
            requireNotNull(System.getProperty(NO_CORE_PLUGIN_PROP)) {
                "System property `$NO_CORE_PLUGIN_PROP` is not set — run via " +
                    "`./gradlew :sample-intellij-plugin:stockInjectUiTest`."
            }
        val path = Path.of(raw)
        require(path.exists()) {
            "No-core plugin zip $path does not exist. Run " +
                "`:sample-intellij-plugin:buildNoCorePlugin` first."
        }
        return path
    }

    private fun agentRuntimeJarPath(): Path {
        val raw =
            requireNotNull(System.getProperty(AGENT_RUNTIME_JAR_PROP)) {
                "System property `$AGENT_RUNTIME_JAR_PROP` is not set — stockInjectUiTest must " +
                    "wire the :agent-runtime jar path."
            }
        val path = Path.of(raw)
        require(Files.isRegularFile(path)) { "Agent runtime jar not found at $path" }
        return path
    }

    private fun createEmptyProject(): Path {
        val base = Path.of(System.getProperty("java.io.tmpdir"), "spectre-stock-inject-uitest")
        base.createDirectories()
        return Files.createTempDirectory(base, "project-").toRealPath().also { dir ->
            dir.toFile().deleteOnExit()
        }
    }

    /**
     * Prefer [candidatePid] when it is a live JVM visible to Attach. On Linux, ide-starter may
     * expose an xvfb-run wrapper — walk that process tree and pick an attachable descendant that
     * looks like IDEA (never a machine-wide "newest IntelliJ" guess — Codex #381).
     */
    private fun resolveIdeJvmPid(candidatePid: Long): Long {
        val listed = runCatching { SpectreProcesses.listJvmProcesses() }.getOrDefault(emptyList())
        val attachableByPid = listed.associateBy { it.pid }
        if (attachableByPid.containsKey(candidatePid)) return candidatePid

        val root =
            ProcessHandle.of(candidatePid).orElse(null)
                ?: error(
                    "Candidate pid $candidatePid is not a live process and is not attach-visible. " +
                        "Attach-visible JVMs: ${listed.map { "${it.pid}:${it.displayName}" }}"
                )
        val treePids = buildList {
            add(root.pid())
            root.descendants().forEach { add(it.pid()) }
        }
        val inTree =
            treePids
                .mapNotNull { attachableByPid[it] }
                .filter { info ->
                    val name = info.displayName.lowercase()
                    ("idea" in name || "intellij" in name) && "jps" !in name
                }
        if (inTree.isNotEmpty()) {
            // Prefer the oldest suitable descendant in this tree (main IDE JVM, not helpers).
            return inTree.minBy { it.pid }.pid
        }
        val anyInTree = treePids.mapNotNull { attachableByPid[it] }
        require(anyInTree.isNotEmpty()) {
            "Could not resolve IDE JVM pid under process tree of $candidatePid. " +
                "Tree pids=$treePids; attach-visible=${listed.map { "${it.pid}:${it.displayName}" }}"
        }
        return anyInTree.minBy { it.pid }.pid
    }

    private fun waitForInjectLog(logPath: Path, deadlineMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline) {
            if (logPath.exists()) {
                val text = logPath.readText()
                if (INJECT_LOG_MARKER in text) return true
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return logPath.exists() && INJECT_LOG_MARKER in logPath.readText()
    }

    private companion object {
        const val IDE_BUILD_NUMBER = "262.8665.337"
        const val TOOL_WINDOW_ID = "Spectre Sample"
        const val NO_CORE_PLUGIN_PROP = "path.to.no.core.plugin"
        const val AGENT_RUNTIME_JAR_PROP = "dev.sebastiano.spectre.agent.runtimeJar"
        const val INJECT_LOG_MARKER = "spectre-core not on target classpath; injecting"
        const val TAG_WAIT_TIMEOUT_MS: Long = 60_000
        const val INJECT_LOG_POLL_MS: Long = 15_000
        const val POLL_INTERVAL_MS: Long = 250
        const val LOG_TAIL_CHARS: Int = 4_000
        val INDICATOR_QUIESCENCE_TIMEOUT = 3.minutes
        val PROJECT_OPEN_TIMEOUT = 5.minutes
    }
}

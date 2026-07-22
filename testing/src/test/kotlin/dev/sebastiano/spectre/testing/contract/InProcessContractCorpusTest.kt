package dev.sebastiano.spectre.testing.contract

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * In-process leg of the shared automator contract corpus (#198).
 *
 * Uses a headless Robot and disables window discovery so this runs on the default headless `:check`
 * job. Assertions are transport-liveness (empty lists OK); fixture-backed semantics are claimed for
 * the agent transport under Xvfb/macOS instead.
 */
class InProcessContractCorpusTest {

    @Test
    fun `contract corpus passes on in-process headless automator`() {
        val automator =
            ComposeAutomator.inProcess(
                robotDriver = RobotDriver.headless(),
                discoverWindows = false,
            )
        InProcessContractDriver(automator).use { driver ->
            AutomatorContractCorpus.run(driver).requireAllPassed()
        }
    }
}

private class InProcessContractDriver(private val automator: ComposeAutomator) :
    AutomatorContractDriver {
    override val transport: AutomatorTransport = AutomatorTransport.InProcess
    override val expectsFixtureSemantics: Boolean = false

    override fun windows(): List<ContractWindow> =
        automator.surfaceIds().map { ContractWindow(surfaceId = it) }

    override fun allNodes(): List<ContractNode> =
        automator.allNodes().map { node ->
            ContractNode(key = node.key.toString(), testTag = node.testTag, text = node.text)
        }

    override fun findByTestTag(tag: String): List<ContractNode> =
        automator.findByTestTag(tag).map { node ->
            ContractNode(key = node.key.toString(), testTag = node.testTag, text = node.text)
        }

    override fun click(nodeKey: String) {
        val node =
            automator.allNodes().firstOrNull { it.key.toString() == nodeKey }
                ?: error("No in-process node for key $nodeKey")
        runBlocking { automator.click(node) }
    }

    override fun typeText(text: String) {
        runBlocking { automator.typeText(text) }
    }

    override fun screenshotProbe(): ScreenshotProbe? {
        val image = automator.screenshot()
        return ScreenshotProbe(
            byteCount = image.width * image.height,
            formatHint = "buffered-image",
        )
    }
}

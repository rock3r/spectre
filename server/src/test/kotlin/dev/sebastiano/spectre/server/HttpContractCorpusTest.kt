@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.testing.contract.AutomatorContractCorpus
import dev.sebastiano.spectre.testing.contract.AutomatorContractDriver
import dev.sebastiano.spectre.testing.contract.AutomatorTransport
import dev.sebastiano.spectre.testing.contract.ContractNode
import dev.sebastiano.spectre.testing.contract.ContractWindow
import dev.sebastiano.spectre.testing.contract.ScreenshotProbe
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * HTTP leg of the shared automator contract corpus (#198).
 *
 * Boots a real CIO server and drives [HttpComposeAutomator] — the same public client path users
 * call. Headless backing automator: empty window/node lists are OK; unknown-key click must fail.
 */
class HttpContractCorpusTest {

    private lateinit var server: EmbeddedServer<*, *>
    private var port: Int = -1

    @BeforeTest
    fun startServer() {
        val automator =
            ComposeAutomator.inProcess(
                robotDriver = RobotDriver.headless(),
                discoverWindows = false,
            )
        server =
            embeddedServer(CIO, port = 0) { installSpectreRoutes(automator) }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(gracePeriodMillis = 0L, timeoutMillis = 0L)
    }

    @Test
    fun `contract corpus passes on HttpComposeAutomator`() {
        HttpContractDriver(port).use { driver ->
            AutomatorContractCorpus.run(driver).requireAllPassed()
        }
    }
}

private class HttpContractDriver(port: Int) : AutomatorContractDriver {
    private val client: HttpComposeAutomator =
        ComposeAutomator.http(host = "127.0.0.1", port = port)

    override val transport: AutomatorTransport = AutomatorTransport.Http
    override val expectsFixtureSemantics: Boolean = false

    override fun windows(): List<ContractWindow> = runBlocking {
        client.windows().map { ContractWindow(surfaceId = it.surfaceId) }
    }

    override fun allNodes(): List<ContractNode> = runBlocking {
        client.allNodes().map {
            ContractNode(
                key = it.key,
                testTag = it.testTag,
                text = it.texts.firstOrNull() ?: it.editableText,
            )
        }
    }

    override fun findByTestTag(tag: String): List<ContractNode> = runBlocking {
        client.findByTestTag(tag).map {
            ContractNode(
                key = it.key,
                testTag = it.testTag,
                text = it.texts.firstOrNull() ?: it.editableText,
            )
        }
    }

    override fun findByText(text: String, exact: Boolean): List<ContractNode> = runBlocking {
        client.findByText(text, exact).map {
            ContractNode(
                key = it.key,
                testTag = it.testTag,
                text = it.texts.firstOrNull() ?: it.editableText,
            )
        }
    }

    override fun findByContentDescription(description: String): List<ContractNode> = runBlocking {
        client.findByContentDescription(description).map {
            ContractNode(
                key = it.key,
                testTag = it.testTag,
                text = it.texts.firstOrNull() ?: it.editableText,
            )
        }
    }

    override fun findByRole(role: String): List<ContractNode> = runBlocking {
        client.findByRole(role).map {
            ContractNode(
                key = it.key,
                testTag = it.testTag,
                text = it.texts.firstOrNull() ?: it.editableText,
            )
        }
    }

    override fun click(nodeKey: String) {
        runBlocking { client.click(nodeKey) }
    }

    override fun typeText(text: String) {
        runBlocking { client.typeText(text) }
    }

    override fun doubleClick(nodeKey: String) {
        runBlocking { client.doubleClick(nodeKey) }
    }

    override fun swipe(fromNodeKey: String, toNodeKey: String) {
        runBlocking { client.swipe(fromNodeKey, toNodeKey) }
    }

    override fun scrollWheel(nodeKey: String, wheelClicks: Int) {
        runBlocking { client.scrollWheel(nodeKey, wheelClicks) }
    }

    override fun pressKey(keyCode: Int, modifiers: Int) {
        runBlocking { client.pressKey(keyCode, modifiers) }
    }

    override fun screenshotProbe(): ScreenshotProbe? = runBlocking {
        val image = client.screenshot()
        ScreenshotProbe(byteCount = image.width * image.height, formatHint = "buffered-image")
    }

    override fun close() {
        client.close()
    }
}

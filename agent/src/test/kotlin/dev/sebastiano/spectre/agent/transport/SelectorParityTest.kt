@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** #202: findByText / contentDescription / role over agent IPC. */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class SelectorParityTest {
    private val udsPath: Path =
        udsBase().resolve("sp-sel-${UUID.randomUUID().toString().take(8)}.sock")

    private val sample =
        NodeSnapshotDto(
            key = "s:0:1",
            testTag = "Submit",
            texts = listOf("OK"),
            role = "Button",
            contentDescription = "Confirm",
            contentDescriptions = listOf("Confirm"),
            isVisible = true,
            bounds = RectDto(0, 0, 20, 10),
        )

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `findByText exact returns nodes`() {
        IpcServer(
                udsPath,
                AgentRequestHandler { req ->
                    when (req) {
                        is AgentRequest.FindByText -> {
                            assertEquals("OK", req.text)
                            assertEquals(true, req.exact)
                            AgentResponse.Nodes(listOf(sample))
                        }
                        else -> AgentResponse.Ok
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    val nodes =
                        assertIs<AgentResponse.Nodes>(
                            client.send(AgentRequest.FindByText(text = "OK", exact = true))
                        )
                    assertEquals(1, nodes.nodes.size)
                    assertEquals("Button", nodes.nodes.single().role)
                }
            }
    }

    @Test
    fun `findByRole and contentDescription round-trip`() {
        IpcServer(
                udsPath,
                AgentRequestHandler { req ->
                    when (req) {
                        is AgentRequest.FindByRole -> AgentResponse.Nodes(listOf(sample))
                        is AgentRequest.FindByContentDescription ->
                            AgentResponse.Nodes(listOf(sample))
                        else -> AgentResponse.Ok
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    assertIs<AgentResponse.Nodes>(client.send(AgentRequest.FindByRole("Button")))
                    assertIs<AgentResponse.Nodes>(
                        client.send(AgentRequest.FindByContentDescription("Confirm"))
                    )
                }
            }
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS path $path did not appear")
    }

    private fun udsBase(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
            Path.of(System.getProperty("java.io.tmpdir"))
        else Path.of("/tmp")
}

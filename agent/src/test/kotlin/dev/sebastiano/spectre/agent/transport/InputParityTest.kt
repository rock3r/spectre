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

/** #203: doubleClick / longClick / swipe / scrollWheel / pressKey over agent IPC. */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class InputParityTest {
    private val udsPath: Path =
        udsBase().resolve("sp-in-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `doubleClick longClick scrollWheel pressKey round-trip`() {
        val seen = mutableListOf<String>()
        IpcServer(
                udsPath,
                AgentRequestHandler { req ->
                    when (req) {
                        is AgentRequest.DoubleClick -> {
                            seen += "doubleClick:${req.nodeKey}"
                            AgentResponse.Ok
                        }
                        is AgentRequest.LongClick -> {
                            seen += "longClick:${req.nodeKey}:${req.holdForMs}"
                            AgentResponse.Ok
                        }
                        is AgentRequest.ScrollWheel -> {
                            seen += "scrollWheel:${req.nodeKey}:${req.wheelClicks}"
                            AgentResponse.Ok
                        }
                        is AgentRequest.PressKey -> {
                            seen += "pressKey:${req.keyCode}:${req.modifiers}"
                            AgentResponse.Ok
                        }
                        else -> AgentResponse.Error("unexpected $req")
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    assertIs<AgentResponse.Ok>(client.send(AgentRequest.DoubleClick("k1")))
                    assertIs<AgentResponse.Ok>(
                        client.send(AgentRequest.LongClick(nodeKey = "k1", holdForMs = 600))
                    )
                    assertIs<AgentResponse.Ok>(
                        client.send(AgentRequest.ScrollWheel(nodeKey = "k1", wheelClicks = 3))
                    )
                    assertIs<AgentResponse.Ok>(
                        client.send(AgentRequest.PressKey(keyCode = 10, modifiers = 128))
                    )
                }
            }
        assertEquals(
            listOf("doubleClick:k1", "longClick:k1:600", "scrollWheel:k1:3", "pressKey:10:128"),
            seen,
        )
    }

    @Test
    fun `swipe node-to-node and coordinate forms round-trip`() {
        val seen = mutableListOf<String>()
        IpcServer(
                udsPath,
                AgentRequestHandler { req ->
                    when (req) {
                        is AgentRequest.Swipe -> {
                            seen +=
                                if (req.fromNodeKey != null) {
                                    "nodes:${req.fromNodeKey}->${req.toNodeKey}:${req.steps}"
                                } else {
                                    "coords:${req.startX},${req.startY}->${req.endX},${req.endY}"
                                }
                            AgentResponse.Ok
                        }
                        else -> AgentResponse.Error("unexpected $req")
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    assertIs<AgentResponse.Ok>(
                        client.send(
                            AgentRequest.Swipe(fromNodeKey = "a", toNodeKey = "b", steps = 8)
                        )
                    )
                    assertIs<AgentResponse.Ok>(
                        client.send(AgentRequest.Swipe(startX = 1, startY = 2, endX = 3, endY = 4))
                    )
                }
            }
        assertEquals(listOf("nodes:a->b:8", "coords:1,2->3,4"), seen)
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

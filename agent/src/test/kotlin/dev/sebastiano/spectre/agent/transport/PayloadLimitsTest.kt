@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * #204: oversized responses fail closed with taxonomy `payloadTooLarge`, never hang or truncate.
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class PayloadLimitsTest {
    private val udsPath: Path =
        udsBase().resolve("sp-pl-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `oversized screenshot response returns payloadTooLarge`() {
        // A PNG larger than the frame budget once wrapped in OpResponse CBOR.
        val hugePng = ByteArray(MAX_FRAME_BYTES) { 0xAB.toByte() }
        IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        is AgentRequest.Screenshot -> AgentResponse.Screenshot(pngBytes = hugePng)
                        AgentRequest.Ping -> AgentResponse.Pong
                        else -> AgentResponse.Ok
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    val response =
                        client.send(
                            AgentRequest.Screenshot(
                                windowIndex = 0,
                                surfaceId = null,
                                fullscreen = false,
                            )
                        )
                    val err = assertIs<AgentResponse.Error>(response)
                    assertEquals(AgentErrorCategory.PayloadTooLarge.wireName, err.category)
                    assertTrue(
                        err.message.contains("MAX_FRAME_BYTES") ||
                            err.message.contains("payload", ignoreCase = true),
                        "expected size-limit message; got: ${err.message}",
                    )
                    // Connection stays usable after a payloadTooLarge error.
                    assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
                }
            }
    }

    @Test
    fun `encoded OpResponse Error for payloadTooLarge fits under frame cap`() {
        val err =
            AgentResponse.Error(
                message =
                    "Response payload size ${MAX_FRAME_BYTES + 1} exceeds " +
                        "MAX_FRAME_BYTES=$MAX_FRAME_BYTES",
                category = AgentErrorCategory.PayloadTooLarge.wireName,
            )
        val bytes = WireCodec.encode(OpResponse(opId = 1L, body = err))
        assertTrue(
            bytes.size < MAX_FRAME_BYTES,
            "taxonomy error itself must always fit under the frame cap (${bytes.size})",
        )
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS path $path did not appear within ${timeoutMs}ms")
    }

    private fun udsBase(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
            Path.of(System.getProperty("java.io.tmpdir"))
        else Path.of("/tmp")
}

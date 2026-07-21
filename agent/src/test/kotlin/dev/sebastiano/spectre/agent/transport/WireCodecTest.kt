@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Round-trip tests for [WireCodec]. Each variant of [AgentRequest] / [AgentResponse] gets a
 * dedicated test so a future field rename or `@SerialName` slip surfaces immediately.
 */
class WireCodecTest {
    @Test
    fun `Ping round-trips`() {
        val encoded = WireCodec.encode(AgentRequest.Ping)
        assertEquals(AgentRequest.Ping, WireCodec.decodeRequest(encoded))
    }

    @Test
    fun `Windows request round-trips`() {
        val encoded = WireCodec.encode(AgentRequest.Windows)
        assertEquals(AgentRequest.Windows, WireCodec.decodeRequest(encoded))
    }

    @Test
    fun `AllNodes request round-trips`() {
        val encoded = WireCodec.encode(AgentRequest.AllNodes)
        assertEquals(AgentRequest.AllNodes, WireCodec.decodeRequest(encoded))
    }

    @Test
    fun `FindByTestTag round-trips`() {
        val req = AgentRequest.FindByTestTag(tag = "submit-button")
        assertEquals(req, WireCodec.decodeRequest(WireCodec.encode(req)))
    }

    @Test
    fun `Click round-trips`() {
        val req = AgentRequest.Click(nodeKey = "surface-0:1:42")
        assertEquals(req, WireCodec.decodeRequest(WireCodec.encode(req)))
    }

    @Test
    fun `TypeText round-trips with unicode payload`() {
        val req = AgentRequest.TypeText(text = "hello 🦀 world café")
        assertEquals(req, WireCodec.decodeRequest(WireCodec.encode(req)))
    }

    @Test
    fun `request log label redacts TypeText payload`() {
        val req = AgentRequest.TypeText(text = "password=super-secret")

        assertEquals("typeText", req.logLabel)
        assertFalse(req.logLabel.contains("super-secret"))
        assertFalse(req.logLabel.contains(req.text))
    }

    @Test
    fun `Screenshot request round-trips`() {
        val encoded = WireCodec.encode(AgentRequest.Screenshot)
        assertEquals(AgentRequest.Screenshot, WireCodec.decodeRequest(encoded))
    }

    @Test
    fun `Detach request round-trips`() {
        val encoded = WireCodec.encode(AgentRequest.Detach)
        assertEquals(AgentRequest.Detach, WireCodec.decodeRequest(encoded))
    }

    @Test
    fun `Pong response round-trips`() {
        assertEquals(
            AgentResponse.Pong,
            WireCodec.decodeResponse(WireCodec.encode(AgentResponse.Pong)),
        )
    }

    @Test
    fun `Ok response round-trips`() {
        assertEquals(AgentResponse.Ok, WireCodec.decodeResponse(WireCodec.encode(AgentResponse.Ok)))
    }

    @Test
    fun `Windows response with multiple entries round-trips`() {
        val resp =
            AgentResponse.Windows(
                listOf(
                    WindowSummaryDto(
                        index = 0,
                        surfaceId = "surface-0",
                        title = "Main",
                        isPopup = false,
                        bounds = RectDto(x = 100, y = 200, width = 800, height = 600),
                    ),
                    WindowSummaryDto(
                        index = 1,
                        surfaceId = "popup-1",
                        title = null,
                        isPopup = true,
                        bounds = RectDto(x = 300, y = 400, width = 200, height = 100),
                    ),
                )
            )

        assertEquals(resp, WireCodec.decodeResponse(WireCodec.encode(resp)))
    }

    @Test
    fun `Nodes response round-trips`() {
        val resp =
            AgentResponse.Nodes(
                listOf(
                    NodeSnapshotDto(
                        key = "surface-0:0:1",
                        testTag = "submit",
                        texts = listOf("Submit"),
                        editableText = "edited",
                        role = "Button",
                        contentDescription = null,
                        isFocused = true,
                        isVisible = true,
                        bounds = RectDto(10, 20, 100, 40),
                    )
                )
            )
        assertEquals(resp, WireCodec.decodeResponse(WireCodec.encode(resp)))
    }

    @Test
    fun `Screenshot response round-trips and preserves bytes`() {
        val pngBytes = ByteArray(256) { (it xor 0xAA).toByte() }
        val resp = AgentResponse.Screenshot(pngBytes)
        val decoded = WireCodec.decodeResponse(WireCodec.encode(resp)) as AgentResponse.Screenshot

        assertEquals(resp, decoded) // uses our explicit equals
    }

    @Test
    fun `Error response round-trips`() {
        val resp = AgentResponse.Error("Something went wrong: NPE at line 42")
        assertEquals(resp, WireCodec.decodeResponse(WireCodec.encode(resp)))
    }

    @Test
    fun `Detached response round-trips`() {
        assertEquals(
            AgentResponse.Detached,
            WireCodec.decodeResponse(WireCodec.encode(AgentResponse.Detached)),
        )
    }

    @Test
    fun `WindowIdentity request round-trips with null and concrete index`() {
        val all = AgentRequest.WindowIdentity(windowIndex = null)
        assertEquals(all, WireCodec.decodeRequest(WireCodec.encode(all)))
        val one = AgentRequest.WindowIdentity(windowIndex = 2)
        assertEquals(one, WireCodec.decodeRequest(WireCodec.encode(one)))
    }

    @Test
    fun `request log label for WindowIdentity does not embed index`() {
        assertEquals("windowIdentity", AgentRequest.WindowIdentity(windowIndex = 7).logLabel)
    }

    @Test
    fun `WindowIdentities response round-trips including null handle and crop flag`() {
        val resp =
            AgentResponse.WindowIdentities(
                listOf(
                    WindowIdentityDto(
                        index = 0,
                        surfaceId = "surface-0",
                        title = "Main",
                        isPopup = false,
                        nativeHandle = 0x1A2B3C4DL,
                        cropRequired = true,
                        windowBoundsOnScreen = RectDto(10, 20, 800, 600),
                        surfaceBoundsOnScreen = RectDto(18, 48, 784, 552),
                        surfaceBoundsInWindow = RectDto(8, 28, 784, 552),
                        scaleX = 2.0,
                        scaleY = 2.0,
                    ),
                    WindowIdentityDto(
                        index = 1,
                        surfaceId = "popup-1",
                        title = null,
                        isPopup = true,
                        nativeHandle = null,
                        cropRequired = false,
                        windowBoundsOnScreen = RectDto(0, 0, 100, 100),
                        surfaceBoundsOnScreen = RectDto(0, 0, 100, 100),
                        surfaceBoundsInWindow = RectDto(0, 0, 100, 100),
                        scaleX = 1.0,
                        scaleY = 1.0,
                    ),
                )
            )
        assertEquals(resp, WireCodec.decodeResponse(WireCodec.encode(resp)))
    }
}

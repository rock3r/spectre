package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Fullscreen stills must carry screen pixels so a `spectre screenshot --fullscreen` PNG matches a
 * recording of the same desktop. The handler reaches the target's `ComposeAutomator` reflectively
 * and agent/target can ship different Spectre versions, so the device-scale call is preferred when
 * present and the logical one remains a working fallback.
 */
class ReflectiveAutomatorStillScaleTest {

    @Test
    fun `fullscreen prefers the device-scale still`() {
        var logicalCalls = 0
        var receivedArg: Any? = NOT_INVOKED
        val automator =
            FakeStillAutomator(
                screenshotImpl = {
                    logicalCalls += 1
                    argb(2, 2)
                },
                screenshotAtDeviceScaleImpl = { region ->
                    receivedArg = region
                    argb(4, 4)
                },
            )
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.Screenshot(fullscreen = true))

        check(response is AgentResponse.Screenshot) {
            "expected Screenshot, got ${response::class.simpleName}: $response"
        }
        assertEquals(
            0,
            logicalCalls,
            "fullscreen stills must not go through the logical-size region capture",
        )
        assertEquals(
            null,
            receivedArg,
            "handler must call screenshotAtDeviceScale(null) for explicit fullscreen",
        )
        assertEquals(4, decodePngWidth(response.pngBytes), "PNG must be the device-scale image")
    }

    @Test
    fun `fullscreen falls back when the target predates device-scale stills`() {
        var receivedArg: Any? = NOT_INVOKED
        val automator =
            FakeLegacyStillAutomator(
                screenshotImpl = { region ->
                    receivedArg = region
                    argb(2, 2)
                }
            )
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.Screenshot(fullscreen = true))

        check(response is AgentResponse.Screenshot) {
            "expected Screenshot, got ${response::class.simpleName}: $response"
        }
        assertEquals(
            null,
            receivedArg,
            "older injected cores must still serve fullscreen via screenshot(Rectangle?)",
        )
    }

    @Test
    fun `fullscreen drops to the logical still when the device-scale PNG will not fit the frame`() {
        var logicalCalls = 0
        // Incompressible device-scale still (hundreds of KiB) against a trivially small logical
        // one, so the budget below sits unambiguously between the two encoded sizes.
        val automator =
            FakeStillAutomator(
                screenshotImpl = {
                    logicalCalls += 1
                    argb(LOGICAL_EDGE, LOGICAL_EDGE)
                },
                screenshotAtDeviceScaleImpl = { noise(DEVICE_EDGE, DEVICE_EDGE) },
            )
        val handler = ReflectiveAutomatorHandler(automator, maxStillPngBytes = BUDGET_BETWEEN)

        val response = handler.handle(AgentRequest.Screenshot(fullscreen = true))

        check(response is AgentResponse.Screenshot) {
            "expected Screenshot, got ${response::class.simpleName}: $response"
        }
        assertEquals(1, logicalCalls, "oversized stills must retry through the logical capture")
        assertEquals(
            LOGICAL_EDGE,
            decodePngWidth(response.pngBytes),
            "an oversized fullscreen still must degrade in resolution, not fail the request",
        )
    }

    @Test
    fun `a still too large even at logical resolution says how to fix it`() {
        // Nothing to degrade to: with a budget this small no desktop screenshot fits at any
        // resolution, so the fallback cannot save it and the caller needs the actionable error
        // rather than the framing layer's generic substitution.
        val automator =
            FakeStillAutomator(
                screenshotImpl = { argb(64, 64) },
                screenshotAtDeviceScaleImpl = { argb(128, 128) },
            )
        val handler = ReflectiveAutomatorHandler(automator, maxStillPngBytes = 1)

        val response = handler.handle(AgentRequest.Screenshot(fullscreen = true))

        val error = assertIs<AgentResponse.Error>(response)
        assertEquals(AgentErrorCategory.PayloadTooLarge.wireName, error.category)
        assertTrue(error.message.contains("--max-frame-bytes"), error.message)
    }

    private fun decodePngWidth(pngBytes: ByteArray): Int =
        ImageIO.read(ByteArrayInputStream(pngBytes)).width

    private companion object {
        const val NOT_INVOKED = "<not invoked>"
        const val DEVICE_EDGE: Int = 400
        const val LOGICAL_EDGE: Int = 8
        /** Far above the 8x8 logical PNG, far below the incompressible 400x400 one. */
        const val BUDGET_BETWEEN: Int = 100_000
    }
}

private fun argb(width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

/** A blank image compresses to nothing; noise is what makes a still genuinely large. */
private fun noise(width: Int, height: Int): BufferedImage {
    val random = kotlin.random.Random(width * 31 + height)
    return argb(width, height).also { image ->
        for (y in 0 until height) {
            for (x in 0 until width) image.setRGB(x, y, random.nextInt())
        }
    }
}

/**
 * Minimal stand-in exposing exactly what [ReflectiveAutomatorHandler] looks up for a fullscreen
 * still.
 */
private class FakeStillAutomator(
    private val screenshotImpl: (Rectangle?) -> BufferedImage,
    private val screenshotAtDeviceScaleImpl: (Rectangle?) -> BufferedImage,
) {
    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = emptyList()

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()

    @Suppress("unused") fun screenshot(region: Rectangle?): BufferedImage = screenshotImpl(region)

    @Suppress("unused")
    fun screenshotAtDeviceScale(region: Rectangle?): BufferedImage =
        screenshotAtDeviceScaleImpl(region)
}

/** An injected `core` from before device-scale stills existed: no `screenshotAtDeviceScale`. */
private class FakeLegacyStillAutomator(private val screenshotImpl: (Rectangle?) -> BufferedImage) {
    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = emptyList()

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()

    @Suppress("unused") fun screenshot(region: Rectangle?): BufferedImage = screenshotImpl(region)
}

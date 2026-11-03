package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.RectDto
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #559: on the attach path the daemon takes the native window still itself and sends it to the
 * agent, which pairs it with the semantics snapshot through `captureWithWindowStill`.
 */
class CaptureWithWindowStillHandlerTest {

    @Test
    fun `the daemon's window still is decoded and passed to captureWithWindowStill`() {
        val automator = StillAwareAutomator()
        val handler = ReflectiveAutomatorHandler(automator)

        val response =
            handler.handle(
                AgentRequest.CaptureWithWindowStill(
                    windowIndex = 1,
                    surfaceId = "surface-1",
                    windowStillPng = png(width = 8, height = 6),
                    stillWindowBounds = RectDto(10, 20, 4, 3),
                )
            )

        val capture = assertIs<AgentResponse.Capture>(response)
        assertEquals(1, capture.windowIndex)
        val call = assertNotNull(automator.lastCall)
        assertEquals(1, call.windowIndex)
        assertEquals("surface-1", call.surfaceId)
        assertEquals(8, call.image.width)
        assertEquals(6, call.image.height)
        assertEquals(Rectangle(10, 20, 4, 3), call.stillWindowBounds)
        // The in-process path must not run: it would take a second still inside the target.
        assertEquals(0, automator.inProcessCaptureCalls)
    }

    @Test
    fun `a target core without captureWithWindowStill reports an actionable unsupported error`() {
        val handler = ReflectiveAutomatorHandler(InProcessOnlyAutomator())

        val response =
            handler.handle(
                AgentRequest.CaptureWithWindowStill(
                    windowIndex = 0,
                    surfaceId = "surface-0",
                    windowStillPng = png(width = 2, height = 2),
                    stillWindowBounds = RectDto(0, 0, 2, 2),
                )
            )

        val error = assertIs<AgentResponse.Error>(response)
        assertEquals(AgentErrorCategory.UnsupportedOperation.wireName, error.category)
        assertTrue(error.message.contains("spectre-core"), error.message)
    }

    @Test
    fun `an undecodable window still is rejected before the automator runs`() {
        val automator = StillAwareAutomator()
        val handler = ReflectiveAutomatorHandler(automator)

        val response =
            handler.handle(
                AgentRequest.CaptureWithWindowStill(
                    windowIndex = 0,
                    surfaceId = "surface-0",
                    windowStillPng = byteArrayOf(1, 2, 3),
                    stillWindowBounds = RectDto(0, 0, 2, 2),
                )
            )

        assertIs<AgentResponse.Error>(response)
        assertEquals(null, automator.lastCall)
    }

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }
}

private data class StillCall(
    val windowIndex: Int,
    val surfaceId: String,
    val image: BufferedImage,
    val stillWindowBounds: Rectangle,
)

/** Exposes the reflective surface of a core that supports daemon-provided window stills. */
private open class InProcessOnlyAutomator {
    var inProcessCaptureCalls: Int = 0
        private set

    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = emptyList()

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()

    @Suppress("unused")
    fun capture(windowIndex: Int): Any {
        inProcessCaptureCalls += 1
        return StillCapture()
    }
}

private class StillAwareAutomator : InProcessOnlyAutomator() {
    var lastCall: StillCall? = null
        private set

    @Suppress("unused")
    fun captureWithWindowStill(
        windowIndex: Int,
        surfaceId: String,
        windowStill: BufferedImage,
        stillWindowBounds: Rectangle,
    ): Any {
        lastCall = StillCall(windowIndex, surfaceId, windowStill, stillWindowBounds)
        return StillCapture()
    }
}

private class StillCapture(
    private val document: Any = StillDocument(),
    private val pngBytes: ByteArray = byteArrayOf(1),
    private val captureJson: String = """{"schemaVersion":1}""",
) {
    @Suppress("unused") fun getDocument(): Any = document

    @Suppress("unused") fun getPngBytes(): ByteArray = pngBytes

    @Suppress("unused") fun getCaptureJson(): String = captureJson
}

private class StillDocument(
    private val schemaVersion: Int = 1,
    private val summary: Any = StillSummary(),
) {
    @Suppress("unused") fun getSchemaVersion(): Int = schemaVersion

    @Suppress("unused") fun getSummary(): Any = summary
}

/** Every counter shares one value: the handler maps them through but this test does not. */
private class StillSummary(private val count: Int = 1) {
    @Suppress("unused") fun getNodeCount(): Int = count

    @Suppress("unused") fun getTaggedNodeCount(): Int = count

    @Suppress("unused") fun getTextedNodeCount(): Int = count

    @Suppress("unused") fun getImageWidth(): Int = count

    @Suppress("unused") fun getImageHeight(): Int = count

    @Suppress("unused") fun getCaptureDurationMs(): Long = count.toLong()
}

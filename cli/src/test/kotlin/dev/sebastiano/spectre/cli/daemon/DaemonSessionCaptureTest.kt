package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AtomicCaptureResult
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * #559: `spectre capture` on the attach path takes the native window still in the daemon, through
 * the daemon's own helper, and never lets the target fall back to a `java.awt.Robot` desktop crop.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonSessionCaptureTest {

    @Test
    fun `capture takes the window still in the daemon and pairs it with the target's tree`() {
        val fake = FakeCaptureSeams(identities = listOf(identity(index = 0, title = "Fixture")))

        val result = fake.capture().capture(windowIndex = 0)

        assertSame(fake.result, result)
        assertEquals(listOf("Fixture" to TARGET_PID), fake.stillRequests)
        val sent = assertNotNull(fake.sentStill)
        assertEquals(0, sent.windowIndex)
        assertEquals("surface-0", sent.surfaceId)
        assertEquals("Fixture", sent.title)
        assertEquals(WINDOW_BOUNDS, sent.stillWindowBounds)
        val decoded = ImageIO.read(ByteArrayInputStream(sent.png))
        assertEquals(6, decoded.width)
        assertEquals(4, decoded.height)
        assertEquals(0, fake.inProcessCalls)
    }

    @Test
    fun `a blank window title fails with an actionable error and no capture`() {
        val fake = FakeCaptureSeams(identities = listOf(identity(index = 0, title = " ")))

        val error = assertFailsWith<IOException> { fake.capture().capture(windowIndex = 0) }

        assertTrue(error.message.orEmpty().contains("title"), error.message)
        assertTrue(fake.stillRequests.isEmpty())
        assertEquals(0, fake.inProcessCalls)
    }

    @Test
    fun `an out of range window index fails`() {
        val fake = FakeCaptureSeams(identities = listOf(identity(index = 0, title = "App")))

        assertFailsWith<IOException> { fake.capture().capture(windowIndex = 3) }
        assertTrue(fake.stillRequests.isEmpty())
    }

    @Test
    fun `a failed native still is reported, never replaced by an in-target capture`() {
        val fake =
            FakeCaptureSeams(
                identities = listOf(identity(index = 0, title = "App")),
                still = { _, _ -> error("spectre-screencapture could not find the window") },
            )

        val error = assertFailsWith<IOException> { fake.capture().capture(windowIndex = 0) }

        assertTrue(error.message.orEmpty().contains("could not find the window"), error.message)
        assertEquals(0, fake.inProcessCalls)
        assertEquals(null, fake.sentStill)
    }

    @Test
    fun `platforms without an out-of-process still use the target's own native capture`() {
        val fake =
            FakeCaptureSeams(
                identities = listOf(identity(index = 0, title = "App")),
                still = { _, _ -> throw UnsupportedOperationException("not on Linux") },
            )

        val result = fake.capture().capture(windowIndex = 0)

        assertSame(fake.result, result)
        assertEquals(1, fake.inProcessCalls)
        assertEquals(null, fake.sentStill)
    }

    @Test
    fun `an untitled window is captured by its frame in the daemon`() {
        val fake =
            FakeCaptureSeams(
                identities = listOf(identity(index = 1, title = null, screenBounds = SCREEN_BOUNDS))
            )

        val result = fake.capture().capture(windowIndex = 1)

        assertSame(fake.result, result)
        assertEquals(
            listOf(FrameRequest(WINDOW_BOUNDS, SCREEN_BOUNDS, 2.0, TARGET_PID)),
            fake.frameRequests,
        )
        assertTrue(fake.stillRequests.isEmpty(), "an untitled window has no title to search for")
        val sent = assertNotNull(fake.sentUntitledStill)
        assertEquals(1, sent.windowIndex)
        assertEquals("surface-1", sent.surfaceId)
        assertEquals(WINDOW_BOUNDS, sent.stillWindowBounds)
        assertEquals(null, fake.sentStill)
        assertEquals(0, fake.inProcessCalls)
    }

    @Test
    fun `an untitled window from a core without screen bounds fails with an update hint`() {
        val fake = FakeCaptureSeams(identities = listOf(identity(index = 0, title = null)))

        val error = assertFailsWith<IOException> { fake.capture().capture(windowIndex = 0) }

        assertTrue(error.message.orEmpty().contains("spectre-core"), error.message)
        assertTrue(fake.frameRequests.isEmpty())
        assertEquals(0, fake.inProcessCalls)
    }

    @Test
    fun `a failed untitled window still is reported, never replaced by an in-target capture`() {
        val fake =
            FakeCaptureSeams(
                identities =
                    listOf(identity(index = 0, title = null, screenBounds = SCREEN_BOUNDS)),
                frameStill = { error("2 windows match the frame") },
            )

        val error = assertFailsWith<IOException> { fake.capture().capture(windowIndex = 0) }

        assertTrue(error.message.orEmpty().contains("2 windows"), error.message)
        assertEquals(0, fake.inProcessCalls)
        assertEquals(null, fake.sentUntitledStill)
    }

    @Test
    fun `an untitled window without an out-of-process still uses the target's own capture`() {
        val fake =
            FakeCaptureSeams(
                identities =
                    listOf(identity(index = 0, title = null, screenBounds = SCREEN_BOUNDS)),
                frameStill = { throw UnsupportedOperationException("not on Linux") },
            )

        val result = fake.capture().capture(windowIndex = 0)

        assertSame(fake.result, result)
        assertEquals(1, fake.inProcessCalls)
        assertEquals(null, fake.sentUntitledStill)
    }

    private data class FrameRequest(
        val windowBounds: RectDto,
        val screenBounds: RectDto,
        val scale: Double,
        val pid: Long,
    )

    private class SentStill(
        val windowIndex: Int,
        val surfaceId: String,
        val title: String,
        val png: ByteArray,
        val stillWindowBounds: RectDto,
    )

    private class FakeCaptureSeams(
        private val identities: List<WindowIdentityDto>,
        private val still: (String, Long) -> BufferedImage = { _, _ ->
            BufferedImage(6, 4, BufferedImage.TYPE_INT_ARGB)
        },
        private val frameStill: () -> BufferedImage = {
            BufferedImage(6, 4, BufferedImage.TYPE_INT_ARGB)
        },
    ) {
        val result: AtomicCaptureResult =
            AtomicCaptureResult(
                windowIndex = 0,
                schemaVersion = 1,
                captureJson = """{"schemaVersion":1}""",
                pngBytes = byteArrayOf(1),
                nodeCount = 0,
                taggedNodeCount = 0,
                textedNodeCount = 0,
                imageWidth = 6,
                imageHeight = 4,
                captureDurationMs = 0,
            )
        val stillRequests: MutableList<Pair<String, Long>> = mutableListOf()
        var sentStill: SentStill? = null
        val frameRequests: MutableList<FrameRequest> = mutableListOf()
        var sentUntitledStill: SentStill? = null
        var inProcessCalls: Int = 0

        fun capture(): DaemonSessionCapture =
            DaemonSessionCapture(
                targetPid = TARGET_PID,
                windowIdentities = { index ->
                    if (index == null) identities else identities.filter { it.index == index }
                },
                captureWindowByTitle = { title, pid ->
                    stillRequests += title to pid
                    still(title, pid)
                },
                captureWithWindowStill = { index, surfaceId, title, png, bounds ->
                    sentStill = SentStill(index, surfaceId, title, png, bounds)
                    result
                },
                captureWithUntitledWindowStill = { index, surfaceId, png, bounds ->
                    sentUntitledStill = SentStill(index, surfaceId, "", png, bounds)
                    result
                },
                captureWindowByFrame = { windowBounds, screenBounds, scaleX, _, pid ->
                    frameRequests += FrameRequest(windowBounds, screenBounds, scaleX, pid)
                    frameStill()
                },
                inProcessCapture = {
                    inProcessCalls += 1
                    result
                },
            )
    }

    private companion object {
        const val TARGET_PID: Long = 4242L
        val WINDOW_BOUNDS: RectDto = RectDto(10, 20, 300, 200)
        val SCREEN_BOUNDS: RectDto = RectDto(0, 0, 1728, 1117)

        fun identity(
            index: Int,
            title: String?,
            screenBounds: RectDto? = null,
        ): WindowIdentityDto =
            WindowIdentityDto(
                index = index,
                surfaceId = "surface-$index",
                title = title,
                isPopup = false,
                nativeHandle = null,
                cropRequired = true,
                windowBoundsOnScreen = WINDOW_BOUNDS,
                surfaceBoundsOnScreen = RectDto(10, 48, 300, 172),
                surfaceBoundsInWindow = RectDto(0, 28, 300, 172),
                scaleX = 2.0,
                scaleY = 2.0,
                screenBoundsOnScreen = screenBounds,
            )
    }
}

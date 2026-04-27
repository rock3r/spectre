package dev.sebastiano.spectre.server.dto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Contract tests for the wire DTOs.
 *
 * The HTTP transport's stability rests on these classes round-tripping through kotlinx
 * serialization without surprises. If a field is renamed, removed, or stops serializing the way it
 * used to, these tests fail loudly so the change is deliberate and the wire contract stays in sync
 * between server and client.
 */
class DtoSerializationTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `RectangleDto round-trips`() {
        val original = RectangleDto(x = 1.5, y = 2.5, width = 100.0, height = 200.0)
        assertEquals(original, json.decodeFromString<RectangleDto>(json.encodeToString(original)))
    }

    @Test
    fun `WindowSummaryDto round-trips`() {
        val original =
            WindowSummaryDto(
                index = 0,
                surfaceId = "main",
                isPopup = false,
                composeSurfaceBounds = RectangleDto(0.0, 0.0, 1280.0, 800.0),
            )
        assertEquals(
            original,
            json.decodeFromString<WindowSummaryDto>(json.encodeToString(original)),
        )
    }

    @Test
    fun `NodeSnapshotDto round-trips with all fields populated`() {
        val original =
            NodeSnapshotDto(
                key = "main:0:42",
                testTag = "Send",
                texts = listOf("Click me"),
                contentDescriptions = listOf("Send button"),
                editableText = null,
                role = "Button",
                isFocused = true,
                isDisabled = false,
                isSelected = true,
                boundsInWindow = RectangleDto(10.0, 20.0, 80.0, 30.0),
                boundsOnScreen = RectangleDto(110.0, 220.0, 80.0, 30.0),
            )
        assertEquals(
            original,
            json.decodeFromString<NodeSnapshotDto>(json.encodeToString(original)),
        )
    }

    @Test
    fun `NodeSnapshotDto round-trips with default-only fields`() {
        // Defaults must survive a round-trip even when the encoded form omits them.
        val original =
            NodeSnapshotDto(
                key = "popup:0:1",
                boundsInWindow = RectangleDto(0.0, 0.0, 1.0, 1.0),
                boundsOnScreen = RectangleDto(0.0, 0.0, 1.0, 1.0),
            )
        val encoded = json.encodeToString(original)
        assertEquals(original, json.decodeFromString<NodeSnapshotDto>(encoded))
    }

    @Test
    fun `request and response envelopes round-trip`() {
        assertEquals(
            ClickRequest("main:0:42"),
            json.decodeFromString(json.encodeToString(ClickRequest("main:0:42"))),
        )
        assertEquals(
            TypeTextRequest("hello"),
            json.decodeFromString(json.encodeToString(TypeTextRequest("hello"))),
        )
        val screenshot = ScreenshotResponse(pngBase64 = "iVBORw0=", width = 100, height = 200)
        assertEquals(screenshot, json.decodeFromString(json.encodeToString(screenshot)))
        val nodes = NodesResponse(nodes = emptyList())
        assertEquals(nodes, json.decodeFromString(json.encodeToString(nodes)))
        val windows = WindowsResponse(windows = emptyList())
        assertEquals(windows, json.decodeFromString(json.encodeToString(windows)))
    }
}

package dev.sebastiano.spectre.recording.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class WaylandHelperProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pointer move command uses snake_case wire names`() {
        val encoded = json.encodeToString(Command.serializer(), Command.PointerMove(x = 40, y = 80))
        assertTrue(encoded.contains("\"command\":\"pointer_move\""))
        assertTrue(encoded.contains("\"x\":40"))
        assertTrue(encoded.contains("\"y\":80"))
        val decoded = json.decodeFromString(Command.serializer(), encoded)
        assertEquals(Command.PointerMove(40, 80), decoded)
    }

    @Test
    fun `pointer axis 0 is the portal vertical scroll axis`() {
        assertEquals(0, VERTICAL_POINTER_AXIS)
        val axis = Command.PointerAxis(axis = VERTICAL_POINTER_AXIS, steps = -3)
        val encoded = json.encodeToString(Command.serializer(), axis)
        assertTrue(encoded.contains("\"axis\":0"))
        assertEquals(axis, json.decodeFromString(Command.serializer(), encoded))
    }

    @Test
    fun `pointer button and key commands round-trip`() {
        val button = Command.PointerButton(button = 1024, pressed = true)
        val key = Command.Key(keyCode = 10, pressed = false)
        val axis = Command.PointerAxis(axis = 0, steps = 3)
        assertEquals(
            button,
            json.decodeFromString(
                Command.serializer(),
                json.encodeToString(Command.serializer(), button),
            ),
        )
        assertEquals(
            key,
            json.decodeFromString(
                Command.serializer(),
                json.encodeToString(Command.serializer(), key),
            ),
        )
        assertEquals(
            axis,
            json.decodeFromString(
                Command.serializer(),
                json.encodeToString(Command.serializer(), axis),
            ),
        )
    }

    @Test
    fun `input ack event is distinct from capture events`() {
        val line = """{"event":"input_ack"}"""
        val event = json.decodeFromString(Event.serializer(), line)
        assertEquals(Event.InputAck, event)
    }

    @Test
    fun `remote desktop token filename is not the AWT robot properties path`() {
        val name = remoteDesktopTokenFileName("rd-monitor-embedded")
        assertEquals("wayland-rd-restore-token-rd-monitor-embedded", name)
        assertFalse(name.contains(".java"))
        assertFalse(name.contains("robot"))
        assertFalse(name.contains("screencast"))
    }
}

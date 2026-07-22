package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectreMcpServerTest {
    @Test
    fun `normalizes recording output paths before sending them to the daemon`() {
        val outputPath = "captures/../captures/session.mp4"

        assertEquals(
            Path.of(outputPath).toAbsolutePath().normalize().toString(),
            normalizeRecordingOutputPath(outputPath),
        )
    }

    @Test
    fun `advertises agent-oriented schemas for the initial daemon tool set`() {
        val server =
            SpectreMcpServer.create(request = { error("tools must not run while being listed") })

        assertEquals(
            setOf(
                "list_processes",
                "attach",
                "windows",
                "tree",
                "find",
                "find_text",
                "wait_for_node",
                "wait_for_visual_idle",
                "click",
                "double_click",
                "long_click",
                "swipe",
                "scroll_wheel",
                "press_key",
                "type_text",
                "screenshot",
                "capture",
                "record_start",
                "record_stop",
                "record_status",
            ),
            server.tools.keys,
        )
        assertTrue(server.tools.getValue("click").tool.description.orEmpty().contains("node key"))
        assertEquals(
            listOf("session_id", "node_key"),
            server.tools.getValue("click").tool.inputSchema.required,
        )
        assertTrue(
            server.tools
                .getValue("screenshot")
                .tool
                .description
                .orEmpty()
                .contains("inline", ignoreCase = true)
        )
    }

    @Test
    fun `screenshot tool returns daemon PNG bytes as inline MCP image content`() {
        val result =
            DaemonResponse.Screenshot("session-42", byteArrayOf(1, 2, 3)).screenshotResult()

        val image = result.content.single() as ImageContent
        assertEquals("image/png", image.mimeType)
        assertEquals("AQID", image.data)
    }

    @Test
    fun `screenshot tool preserves daemon error messages`() {
        val result =
            DaemonResponse.Error(
                    DaemonErrorCode.SessionNotFound,
                    "session session-42 was not found",
                )
                .screenshotResult()

        assertTrue(result.isError == true)
        assertEquals(
            "session session-42 was not found",
            (result.content.single() as TextContent).text,
        )
    }
}

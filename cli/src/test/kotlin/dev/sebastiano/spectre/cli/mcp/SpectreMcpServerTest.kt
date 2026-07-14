package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpectreMcpServerTest {
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
                "click",
                "type_text",
                "screenshot",
                "record_start",
                "record_stop",
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
}

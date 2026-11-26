package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SpectreMcpServerRecordingTest {
    @Test
    fun `normalizes recording output paths before sending them to the daemon`() {
        val outputPath = "captures/../captures/session.mp4"

        assertEquals(
            Path.of(outputPath).toAbsolutePath().normalize().toString(),
            normalizeRecordingOutputPath(outputPath),
        )
    }

    @Test
    fun `record stop returns backend diagnostics including unavailable values`() = runBlocking {
        val server = SpectreMcpServer.create { request ->
            assertIs<DaemonRequest.StopRecording>(request)
            DaemonResponse.RecordingStopped(request.sessionId, "/capture.mp4").also {
                it.diagnostics = DaemonResponse.RecordingDiagnostics(null, true)
            }
        }
        val result =
            invokeTool(server, "record_stop", buildJsonObject { put("session_id", "pid-3") })
        val body = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        val diagnostics = body.getValue("diagnostics").jsonObject
        assertEquals("null", diagnostics.getValue("droppedFrames").jsonPrimitive.content)
        assertEquals("true", diagnostics.getValue("cropFixedAtStart").jsonPrimitive.content)
    }
}

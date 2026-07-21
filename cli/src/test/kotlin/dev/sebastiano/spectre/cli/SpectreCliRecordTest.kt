package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SpectreCliRecordTest {
    @Test
    fun `record start prints the caller supplied output path as stable JSON`() {
        val output = StringBuilder()
        val recordingPath = Files.createTempDirectory("spectre-cli-test").resolve("capture.mp4")
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(
                        DaemonRequest.StartRecording(
                            sessionId = "pid-42",
                            outputPath = recordingPath.toString(),
                            windowIndex = 0,
                            fullscreen = false,
                        ),
                        request,
                    )
                    DaemonResponse.RecordingStarted("pid-42", recordingPath.toString())
                },
                output = output,
            )

        assertEquals(
            0,
            cli.run(
                listOf("record", "start", "pid-42", "--output", recordingPath.toString(), "--json")
            ),
        )
        val json = Json.parseToJsonElement(output.toString()).jsonObject
        assertEquals(1, json.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("pid-42", json.getValue("id").jsonPrimitive.content)
        assertEquals(recordingPath.toString(), json.getValue("path").jsonPrimitive.content)
    }

    @Test
    fun `record stop prints the final output path as stable JSON`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.StopRecording("pid-42"), request)
                    DaemonResponse.RecordingStopped("pid-42", "/tmp/capture.mp4")
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("record", "stop", "pid-42", "--json")))
        assertEquals(
            "{\"version\":1,\"id\":\"pid-42\",\"path\":\"/tmp/capture.mp4\"}\n",
            output.toString(),
        )
    }

    @Test
    fun `record start sends a normalized absolute caller output path to the daemon`() {
        val output = StringBuilder()
        val expectedPath = Path.of("capture.mp4").toAbsolutePath().normalize().toString()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(
                        DaemonRequest.StartRecording(
                            sessionId = "pid-42",
                            outputPath = expectedPath,
                            windowIndex = 0,
                            fullscreen = false,
                        ),
                        request,
                    )
                    DaemonResponse.RecordingStarted("pid-42", expectedPath)
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("record", "start", "pid-42", "--output", "capture.mp4")))
        assertEquals("Recording to $expectedPath.\n", output.toString())
    }

    @Test
    fun `record start forwards window and fullscreen targeting`() {
        val seen = mutableListOf<DaemonRequest>()
        val cli =
            SpectreCli(
                request = { request ->
                    seen += request
                    DaemonResponse.RecordingStarted("pid-42", "/tmp/r.mp4")
                },
                output = StringBuilder(),
            )

        assertEquals(0, cli.run(listOf("record", "start", "pid-42", "--window", "2")))
        assertEquals(
            DaemonRequest.StartRecording(
                sessionId = "pid-42",
                outputPath = null,
                windowIndex = 2,
                fullscreen = false,
            ),
            seen.single(),
        )

        seen.clear()
        assertEquals(0, cli.run(listOf("record", "start", "pid-42", "--window-index", "1")))
        assertEquals(
            DaemonRequest.StartRecording(
                sessionId = "pid-42",
                outputPath = null,
                windowIndex = 1,
                fullscreen = false,
            ),
            seen.single(),
        )

        seen.clear()
        assertEquals(0, cli.run(listOf("record", "start", "pid-42", "--fullscreen")))
        assertEquals(
            DaemonRequest.StartRecording(
                sessionId = "pid-42",
                outputPath = null,
                windowIndex = 0,
                fullscreen = true,
            ),
            seen.single(),
        )
    }

    @Test
    fun `record start rejects fullscreen combined with window targeting`() {
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("daemon must not be called for invalid record options") },
                output = StringBuilder(),
                errorOutput = errorOutput,
            )
        assertEquals(
            2,
            cli.run(listOf("record", "start", "pid-42", "--fullscreen", "--window", "0")),
        )
        assertTrue(errorOutput.toString().contains("fullscreen", ignoreCase = true))
    }

    @Test
    fun `record start without output sends null path for daemon allocation`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(
                        DaemonRequest.StartRecording(
                            sessionId = "pid-42",
                            outputPath = null,
                            windowIndex = 0,
                            fullscreen = false,
                        ),
                        request,
                    )
                    DaemonResponse.RecordingStarted(
                        "pid-42",
                        "/tmp/spectre/captures/0001/recording.mp4",
                    )
                },
                output = output,
            )
        assertEquals(0, cli.run(listOf("record", "start", "pid-42")))
        assertTrue(output.toString().contains("Recording to"))
    }

    @Test
    fun `record status prints idle when not recording`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.RecordingStatus("pid-42"), request)
                    DaemonResponse.RecordingStatus("pid-42", active = false)
                },
                output = output,
            )
        assertEquals(0, cli.run(listOf("record", "status", "pid-42")))
        assertTrue(output.toString().contains("No active recording"))
    }
}

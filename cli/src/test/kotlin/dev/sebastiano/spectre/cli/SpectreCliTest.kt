package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonJvmProcessSummary
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionSummary
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SpectreCliTest {
    @Test
    fun `JDK preflight rejects Java versions older than 21`() {
        assertEquals(
            "Spectre requires JDK 21 or later; found Java 17.",
            jdkPreflightError(featureVersion = 17, hasAttachModule = true),
        )
    }

    @Test
    fun `JDK preflight rejects runtimes without jdk attach`() {
        assertEquals(
            "Spectre requires a full JDK with the jdk.attach module; the current Java runtime does not provide it.",
            jdkPreflightError(featureVersion = 21, hasAttachModule = false),
        )
    }

    @Test
    fun `CLI-level JDK preflight permits a Java 21 runtime without jdk attach`() {
        assertEquals(null, minimumJdkPreflightError(featureVersion = 21))
    }

    @Test
    fun `type prints stable JSON completion output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.TypeText("pid-42", "hello"), request)
                    DaemonResponse.Completed("pid-42")
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("type", "pid-42", "hello", "--json")))
        assertEquals("{\"version\":1,\"id\":\"pid-42\"}\n", output.toString())
    }

    @Test
    fun `screenshot writes PNG output and reports its stable JSON path`() {
        val output = StringBuilder()
        val imagePath = Files.createTempFile("spectre-cli-test", ".png")
        Files.deleteIfExists(imagePath)
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.Screenshot("pid-42"), request)
                    DaemonResponse.Screenshot("pid-42", byteArrayOf(1, 2, 3))
                },
                output = output,
            )

        try {
            assertEquals(
                0,
                cli.run(listOf("screenshot", "pid-42", "--output", imagePath.toString(), "--json")),
            )
            assertEquals(byteArrayOf(1, 2, 3).toList(), Files.readAllBytes(imagePath).toList())
            val json = Json.parseToJsonElement(output.toString())
            assertEquals(1, json.jsonObject.getValue("version").jsonPrimitive.content.toInt())
            assertEquals(
                imagePath.toString(),
                json.jsonObject.getValue("path").jsonPrimitive.content,
            )
        } finally {
            Files.deleteIfExists(imagePath)
        }
    }

    @Test
    fun `screenshot reports local output errors separately from daemon errors`() {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val missingDirectory = Files.createTempDirectory("spectre-cli-test").resolve("missing")
        val imagePath = missingDirectory.resolve("capture.png")
        val cli =
            SpectreCli(
                request = { DaemonResponse.Screenshot("pid-42", byteArrayOf(1, 2, 3)) },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(1, cli.run(listOf("screenshot", "pid-42", "--output", imagePath.toString())))
        assertEquals("", output.toString())
        assertTrue(errorOutput.startsWith("Spectre output error:"))
    }

    @Test
    fun `record start prints the caller supplied output path as stable JSON`() {
        val output = StringBuilder()
        val recordingPath = Files.createTempDirectory("spectre-cli-test").resolve("capture.mp4")
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(
                        DaemonRequest.StartRecording("pid-42", recordingPath.toString()),
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
                    assertEquals(DaemonRequest.StartRecording("pid-42", expectedPath), request)
                    DaemonResponse.RecordingStarted("pid-42", expectedPath)
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("record", "start", "pid-42", "--output", "capture.mp4")))
        assertEquals("Recording to $expectedPath.\n", output.toString())
    }

    @Test
    fun `click prints stable JSON completion output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.Click("pid-42", "main:0:1"), request)
                    DaemonResponse.Completed("pid-42")
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("click", "pid-42", "main:0:1", "--json")))
        assertEquals("{\"version\":1,\"id\":\"pid-42\"}\n", output.toString())
    }

    @Test
    fun `find prints stable JSON node output`() {
        val output = StringBuilder()
        val node =
            NodeSnapshotDto(
                key = "main:0:1",
                testTag = "submit",
                texts = listOf("Submit"),
                role = "Button",
                contentDescription = null,
                isVisible = true,
                bounds = RectDto(1, 2, 3, 4),
            )
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.FindByTestTag("pid-42", "submit"), request)
                    DaemonResponse.Nodes("pid-42", listOf(node))
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("find", "pid-42", "submit", "--json")))
        assertEquals(
            "{\"version\":1,\"nodes\":[{\"key\":\"main:0:1\",\"testTag\":\"submit\",\"texts\":[\"Submit\"]," +
                "\"editableText\":null,\"role\":\"Button\",\"contentDescription\":null,\"isFocused\":false," +
                "\"isVisible\":true,\"bounds\":{\"x\":1,\"y\":2,\"width\":3,\"height\":4}}]}\n",
            output.toString(),
        )
    }

    @Test
    fun `tree prints stable JSON node output`() {
        val output = StringBuilder()
        val node =
            NodeSnapshotDto(
                key = "main:0:1",
                testTag = "submit",
                texts = listOf("Submit"),
                role = "Button",
                contentDescription = null,
                isVisible = true,
                bounds = RectDto(1, 2, 3, 4),
            )
        val cli =
            SpectreCli(request = { DaemonResponse.Nodes("pid-42", listOf(node)) }, output = output)

        assertEquals(0, cli.run(listOf("tree", "pid-42", "--json")))
        assertEquals(
            "{\"version\":1,\"nodes\":[{\"key\":\"main:0:1\",\"testTag\":\"submit\",\"texts\":[\"Submit\"]," +
                "\"editableText\":null,\"role\":\"Button\",\"contentDescription\":null,\"isFocused\":false," +
                "\"isVisible\":true,\"bounds\":{\"x\":1,\"y\":2,\"width\":3,\"height\":4}}]}\n",
            output.toString(),
        )
    }

    @Test
    fun `windows prints stable JSON output`() {
        val output = StringBuilder()
        val window = WindowSummaryDto(0, "main", "Fixture", false, RectDto(1, 2, 3, 4))
        val cli =
            SpectreCli(
                request = { DaemonResponse.Windows("pid-42", listOf(window)) },
                output = output,
            )

        assertEquals(0, cli.run(listOf("windows", "pid-42", "--json")))
        assertEquals(
            "{\"version\":1,\"windows\":[{\"index\":0,\"surfaceId\":\"main\",\"title\":\"Fixture\",\"isPopup\":false," +
                "\"bounds\":{\"x\":1,\"y\":2,\"width\":3,\"height\":4}}]}\n",
            output.toString(),
        )
    }

    @Test
    fun `windows uses a readable label for untitled windows`() {
        val output = StringBuilder()
        val window = WindowSummaryDto(0, "popup", null, true, RectDto(1, 2, 3, 4))
        val cli =
            SpectreCli(
                request = { DaemonResponse.Windows("pid-42", listOf(window)) },
                output = output,
            )

        assertEquals(0, cli.run(listOf("windows", "pid-42")))
        assertEquals("0 (untitled)\n", output.toString())
    }

    @Test
    fun `detach prints stable JSON session output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.Detach(sessionId = "pid-42"), request)
                    DaemonResponse.Detached(sessionId = "pid-42")
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("detach", "pid-42", "--json")))
        assertEquals("{\"version\":1,\"id\":\"pid-42\"}\n", output.toString())
    }

    @Test
    fun `detach prints human-readable session output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(request = { DaemonResponse.Detached(sessionId = "pid-42") }, output = output)

        assertEquals(0, cli.run(listOf("detach", "pid-42")))
        assertEquals("Detached pid-42.\n", output.toString())
    }

    @Test
    fun `attach prints stable JSON session output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.Attach(targetPid = 42), request)
                    DaemonResponse.Attached(sessionId = "pid-42", targetPid = 42)
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("attach", "42", "--json")))
        assertEquals("{\"version\":1,\"id\":\"pid-42\",\"pid\":42}\n", output.toString())
    }

    @Test
    fun `attach prints human-readable session output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { DaemonResponse.Attached(sessionId = "pid-42", targetPid = 42) },
                output = output,
            )

        assertEquals(0, cli.run(listOf("attach", "42")))
        assertEquals("pid-42 (pid 42)\n", output.toString())
    }

    @Test
    fun `ps prints stable JSON JVM process output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(
                        ProcessHandle.current().pid(),
                        assertIs<DaemonRequest.ListJvmProcesses>(request).requesterPid,
                    )
                    DaemonResponse.JvmProcesses(
                        listOf(DaemonJvmProcessSummary(pid = 42, displayName = "com.example.App"))
                    )
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("ps", "--json")))
        assertEquals(
            "{\"version\":1,\"processes\":[{\"pid\":42,\"displayName\":\"com.example.App\"}]}\n",
            output.toString(),
        )
    }

    @Test
    fun `ps reports daemon discovery errors without a stack trace`() {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = {
                    DaemonResponse.Error(
                        code = dev.sebastiano.spectre.cli.daemon.DaemonErrorCode.AttachFailed,
                        message = "The JDK Attach API is not available",
                    )
                },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(3, cli.run(listOf("ps")))
        assertEquals("", output.toString())
        assertEquals(
            "Spectre daemon error: The JDK Attach API is not available\n",
            errorOutput.toString(),
        )
    }

    @Test
    fun `daemon status prints stable JSON session output`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { request ->
                    assertEquals(DaemonRequest.ListSessions, request)
                    DaemonResponse.Sessions(
                        listOf(DaemonSessionSummary(sessionId = "pid-42", targetPid = 42))
                    )
                },
                output = output,
            )

        assertEquals(0, cli.run(listOf("daemon", "status", "--json")))
        assertEquals(
            "{\"version\":1,\"sessions\":[{\"id\":\"pid-42\",\"pid\":42}]}\n",
            output.toString(),
        )
    }

    @Test
    fun `daemon status prints human-readable session output`() {
        val output = StringBuilder()
        val cli = SpectreCli(request = { DaemonResponse.Sessions(emptyList()) }, output = output)

        assertEquals(0, cli.run(listOf("daemon", "status")))
        assertEquals("No daemon sessions.\n", output.toString())
    }

    @Test
    fun `daemon kill prints stable JSON confirmation`() {
        val output = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("Kill must not use the auto-starting daemon request path") },
                shutdownRequest = { DaemonResponse.ShuttingDown },
                output = output,
            )

        assertEquals(0, cli.run(listOf("daemon", "kill", "--json")))
        assertEquals("{\"version\":1,\"stopped\":true}\n", output.toString())
    }

    @Test
    fun `usage errors are printed and returned as a nonzero exit code`() {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("Daemon should not be contacted") },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(2, cli.run(listOf("unknown")))
        assertEquals("", output.toString())
        assertTrue(errorOutput.contains("unknown"))
    }

    @Test
    fun `attach failures use the attach exit code`() {
        val cli =
            SpectreCli(
                request = {
                    DaemonResponse.Error(
                        dev.sebastiano.spectre.cli.daemon.DaemonErrorCode.AttachFailed,
                        "JDK Attach API is unavailable",
                    )
                },
                output = StringBuilder(),
                errorOutput = StringBuilder(),
            )

        assertEquals(3, cli.run(listOf("attach", "42")))
    }

    @Test
    fun `missing targets use the target-not-found exit code`() {
        val cli =
            SpectreCli(
                request = {
                    DaemonResponse.Error(
                        dev.sebastiano.spectre.cli.daemon.DaemonErrorCode.SessionNotFound,
                        "session not found",
                    )
                },
                output = StringBuilder(),
                errorOutput = StringBuilder(),
            )

        assertEquals(4, cli.run(listOf("detach", "missing")))
    }

    @Test
    fun `daemon transport failures use the daemon exit code`() {
        val cli =
            SpectreCli(
                request = { throw IOException("Socket unavailable") },
                output = StringBuilder(),
                errorOutput = StringBuilder(),
            )

        assertEquals(5, cli.run(listOf("ps")))
    }

    @Test
    fun `daemon I O errors are reported without a stack trace`() {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli =
            SpectreCli(
                request = { throw IOException("Socket unavailable") },
                output = output,
                errorOutput = errorOutput,
            )

        assertEquals(5, cli.run(listOf("daemon", "status")))
        assertEquals("", output.toString())
        assertEquals("Spectre daemon error: Socket unavailable\n", errorOutput.toString())
    }

    @Test
    fun `daemon status reports a failure from an existing daemon endpoint`() {
        val socketDirectory = Files.createTempDirectory("spectre-cli-status")
        val socketPath = socketDirectory.resolve("daemon.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socketPath))
        }
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val cli = SpectreCli(output = output, errorOutput = errorOutput, socketPath = socketPath)

        try {
            assertEquals(5, cli.run(listOf("daemon", "status")))
            assertEquals("", output.toString())
            assertTrue(errorOutput.startsWith("Spectre daemon error: "))
        } finally {
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(socketDirectory)
        }
    }

    @Test
    fun `daemon request reports attach preflight failures before daemon startup`() {
        val request =
            daemonRequest(
                socketPath = Path.of("/tmp/spectre-test/daemon.sock"),
                attachPreflight = { "The JDK Attach API is not available" },
            )

        assertEquals(
            DaemonResponse.Error(
                code = DaemonErrorCode.AttachFailed,
                message = "The JDK Attach API is not available",
            ),
            request(DaemonRequest.Attach(targetPid = 42)),
        )
    }
}

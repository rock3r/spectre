package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JDK preflight and exit-code / error-reporting behaviour of [SpectreCli]. */
class SpectreCliPreflightAndErrorsTest {
    @Test
    fun `JDK preflight rejects Java versions older than 21`() {
        assertEquals(
            "Spectre requires JDK 21 or later; found Java 17.",
            jdkPreflightError(featureVersion = 17, hasAttachModule = true),
        )
        assertEquals(
            "Spectre requires JDK 21 or later; found Java 20.",
            minimumJdkPreflightError(featureVersion = 20),
        )
    }

    @Test
    fun `JDK preflight accepts Java 21 through 24 without the Leyden cache`() {
        // JDK 25 only adds the Leyden AOT cache (a startup optimization); the CLI and
        // its daemon run on 21+, matching the attach runtime's minimum.
        for (feature in 21..25) {
            assertEquals(null, jdkPreflightError(featureVersion = feature, hasAttachModule = true))
            assertEquals(null, minimumJdkPreflightError(featureVersion = feature))
        }
    }

    @Test
    fun `JDK preflight rejects runtimes without jdk attach`() {
        assertEquals(
            "Spectre requires a full JDK with the jdk.attach module; the current Java runtime does not provide it.",
            jdkPreflightError(featureVersion = 25, hasAttachModule = false),
        )
    }

    @Test
    fun `CLI-level JDK preflight permits a Java 25 runtime without jdk attach`() {
        assertEquals(null, minimumJdkPreflightError(featureVersion = 25))
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
        assertEquals(
            "Spectre daemon error: Socket unavailable\n" +
                "Hint: run \"spectre guide troubleshooting\" or " +
                "\"spectre guide --search <words>\".\n",
            errorOutput.toString(),
        )
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

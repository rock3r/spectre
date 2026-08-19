package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.DEFAULT_MAX_FRAME_BYTES
import dev.sebastiano.spectre.agent.transport.FrameLimits
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * A daemon keeps the frame budget it booted with, so this only holds against a *real* daemon
 * process: the in-process registry would report whatever the test JVM currently has configured.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
@EnabledOnOs(OS.LINUX, OS.MAC)
class DaemonFrameBudgetE2eTest {

    private val socketPath: Path = temporarySocketPath()
    private var daemon: Process? = null

    @AfterTest
    fun cleanUp() {
        daemon?.destroyForcibly()?.waitFor()
        FrameLimits.resetToEnvironment()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketPath.parent)
        Files.deleteIfExists(socketPath.parent.parent)
    }

    @Test
    fun `a daemon reports the budget it booted with`() {
        DaemonClient(socketPath).use { client ->
            // Booted and queried on the same budget: the handshake must not object.
            assertEquals(
                DaemonResponse.Sessions(emptyList()),
                startDaemonWith(client, BOOTED_BUDGET),
            )
        }
    }

    @Test
    fun `an explicit default-sized budget is checked against the daemon too`() {
        DaemonClient(socketPath).use { client ->
            startDaemonWith(client, BOOTED_BUDGET)

            FrameLimits.configure(DEFAULT_MAX_FRAME_BYTES)
            val failure =
                runCatching { client.request(DaemonRequest.ListSessions) }.exceptionOrNull()
                    ?: fail("an explicit request must be checked whatever its value")

            assertTrue(failure.message.orEmpty().contains("64MiB"), failure.message.orEmpty())
        }
    }

    @Test
    fun `asking for a budget the running daemon cannot honour fails loudly`() {
        DaemonClient(socketPath).use { client ->
            startDaemonWith(client, BOOTED_BUDGET)

            FrameLimits.configure(REQUESTED_BUDGET)
            val failure =
                runCatching { client.request(DaemonRequest.ListSessions) }.exceptionOrNull()
                    ?: fail("expected the handshake to refuse the budget")

            assertTrue(failure is IOException, "unexpected failure type: $failure")
            val message = failure.message.orEmpty()
            assertTrue(message.contains("--max-frame-bytes"), message)
            assertTrue(message.contains("256MiB"), "should name what was asked for: $message")
            assertTrue(message.contains("128MiB"), "should name what the daemon runs: $message")
            assertTrue(message.contains("spectre daemon kill"), message)
        }
    }

    @Test
    fun `an unconfigured client works against a daemon on a raised budget`() {
        DaemonClient(socketPath).use { client ->
            startDaemonWith(client, BOOTED_BUDGET)

            FrameLimits.resetToEnvironment()
            assertEquals(
                DaemonResponse.Sessions(emptyList()),
                client.request(DaemonRequest.ListSessions),
            )
        }
    }

    /**
     * Boots a daemon on [budget] and returns its first response. Goes through `requestOrStart` so
     * the startup coordinator owns the socket-appears-before-accept race rather than this test.
     */
    private fun startDaemonWith(client: DaemonClient, budget: Int): DaemonResponse {
        FrameLimits.configure(budget)
        val command =
            DaemonProcessLauncher(
                    socketPath = socketPath,
                    classPath = System.getProperty("java.class.path"),
                )
                .command()
        assertTrue(
            command.contains("--max-frame-bytes"),
            "launcher must pin a non-default budget: $command",
        )
        return client.requestOrStart(DaemonRequest.ListSessions) {
            daemon =
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectErrorStream(false)
                    .start()
        }
    }

    private fun temporarySocketPath(): Path =
        Path.of(
                if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) "/tmp"
                else System.getProperty("java.io.tmpdir"),
                "sp-d-${UUID.randomUUID().toString().take(8)}",
            )
            .resolve("daemon")
            .resolve("daemon.sock")

    private companion object {
        const val BOOTED_BUDGET: Int = 128 * 1024 * 1024
        const val REQUESTED_BUDGET: Int = 256 * 1024 * 1024
    }
}

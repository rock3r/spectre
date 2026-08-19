package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.DEFAULT_MAX_FRAME_BYTES
import dev.sebastiano.spectre.agent.transport.FrameLimits
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon is a separate process from the CLI that spawns it, so its frame budget has to be
 * carried on the daemon command line — otherwise a `--max-frame-bytes` on the CLI would silently
 * apply to only one of the two hops a screenshot travels.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonFrameBudgetTest {

    @Test
    fun `socket path still parses without a budget option`() {
        assertEquals(
            Path.of("/tmp/spectre.sock"),
            DaemonMain.socketPath(listOf("--socket", "/tmp/spectre.sock")),
        )
    }

    @Test
    fun `socket path parses alongside a budget option`() {
        val arguments = listOf("--socket", "/tmp/spectre.sock", "--max-frame-bytes", "1048576")

        assertEquals(Path.of("/tmp/spectre.sock"), DaemonMain.socketPath(arguments))
        assertEquals(1048576, DaemonMain.maxFrameBytes(arguments))
    }

    @Test
    fun `absent budget option means the default applies`() {
        assertNull(DaemonMain.maxFrameBytes(listOf("--socket", "/tmp/spectre.sock")))
    }

    @Test
    fun `an unusable command line still fails loudly`() {
        assertFailsWith<IllegalArgumentException> { DaemonMain.socketPath(listOf("--socket")) }
        assertFailsWith<IllegalArgumentException> { DaemonMain.socketPath(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            DaemonMain.maxFrameBytes(listOf("--socket", "/tmp/s.sock", "--max-frame-bytes", "nope"))
        }
    }

    @Test
    fun `launcher omits the budget option when nothing was asked for`() {
        try {
            FrameLimits.resetToEnvironment()
            val command = DaemonProcessLauncher(Path.of("/tmp/spectre.sock")).command()

            assertTrue(command.contains("--socket"))
            assertTrue(
                !command.contains("--max-frame-bytes"),
                "an unconfigured daemon resolves the same environment: $command",
            )
        } finally {
            FrameLimits.resetToEnvironment()
        }
    }

    @Test
    fun `launcher forwards a configured budget to the daemon process`() {
        try {
            FrameLimits.configure(DEFAULT_MAX_FRAME_BYTES * 2)
            val command = DaemonProcessLauncher(Path.of("/tmp/spectre.sock")).command()

            val index = command.indexOf("--max-frame-bytes")
            assertTrue(index >= 0, "configured budget must reach the daemon: $command")
            assertEquals((DEFAULT_MAX_FRAME_BYTES * 2).toString(), command[index + 1])
        } finally {
            FrameLimits.resetToEnvironment()
        }
    }

    @Test
    fun `launcher forwards an explicit default-sized budget`() {
        // The daemon inherits this process's environment, so `--max-frame-bytes 64MiB` layered
        // over SPECTRE_MAX_FRAME_BYTES=128MiB must be pinned or the daemon re-reads 128MiB.
        try {
            FrameLimits.configure(DEFAULT_MAX_FRAME_BYTES)
            val command = DaemonProcessLauncher(Path.of("/tmp/spectre.sock")).command()

            val index = command.indexOf("--max-frame-bytes")
            assertTrue(index >= 0, "an explicit default must still be pinned: $command")
            assertEquals(DEFAULT_MAX_FRAME_BYTES.toString(), command[index + 1])
        } finally {
            FrameLimits.resetToEnvironment()
        }
    }
}

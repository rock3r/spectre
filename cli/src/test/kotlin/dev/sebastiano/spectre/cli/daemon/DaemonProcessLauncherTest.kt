package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonProcessLauncherTest {
    @Test
    fun `builds a daemon-only Java command for its socket`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                )
                .command()

        assertEquals(
            listOf(
                "/jdk/bin/java",
                "-cp",
                "spectre.jar",
                "dev.sebastiano.spectre.cli.daemon.DaemonMainKt",
                "--socket",
                Path.of("/tmp", "spectre", "daemon.sock").toString(),
            ),
            command,
        )
    }

    // ---- forwarded switches (#472) ----
    //
    // The daemon is a fresh JVM with an explicit command line, so a system property set on the CLI
    // reaches it only if this launcher passes it on. `DaemonSessionRegistry` attaches with
    // `AgentAttach.attach(pid)` and no options, which makes the property the only channel a CLI
    // user has for the coordination opt-out — a `-D` the CLI silently dropped would look exactly
    // like an opt-out that does not work.

    @Test
    fun `forwards the input coordination opt-out to the daemon`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { name ->
                        "disabled"
                            .takeIf { name == "dev.sebastiano.spectre.agent.inputCoordination" }
                    },
                )
                .command()

        assertEquals(
            listOf("-Ddev.sebastiano.spectre.agent.inputCoordination=disabled"),
            command.filter { it.startsWith("-D") },
        )
    }

    @Test
    fun `forwards the agent runtime jar override`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { name ->
                        "/jars/agent.jar"
                            .takeIf { name == "dev.sebastiano.spectre.agent.runtimeJar" }
                    },
                )
                .command()

        assertEquals(
            listOf("-Ddev.sebastiano.spectre.agent.runtimeJar=/jars/agent.jar"),
            command.filter { it.startsWith("-D") },
        )
    }

    @Test
    fun `forwards nothing when nothing is set`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { null },
                )
                .command()

        assertEquals(emptyList(), command.filter { it.startsWith("-D") })
    }
}

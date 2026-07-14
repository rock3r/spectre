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
                "/tmp/spectre/daemon.sock",
            ),
            command,
        )
    }
}

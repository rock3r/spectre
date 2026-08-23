@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoordinatorProcessLauncherTest {

    private val temporaryDirectory = Files.createTempDirectory("spc-p-")
    private val endpoint =
        CoordinatorEndpoint(temporaryDirectory, temporaryDirectory.resolve("coordinator.sock"))
    private var process: Process? = null

    @AfterTest
    fun cleanUp() {
        process?.destroy()
        process?.waitFor(2, TimeUnit.SECONDS)
        Files.deleteIfExists(endpoint.socketPath)
        Files.deleteIfExists(temporaryDirectory.resolve("coordinator.lock"))
        Files.deleteIfExists(temporaryDirectory.resolve("recovery.properties.tmp"))
        Files.deleteIfExists(temporaryDirectory.resolve("recovery.properties"))
        Files.deleteIfExists(temporaryDirectory)
    }

    @Test
    fun `launcher builds a dedicated coordinator Java command`() {
        val launcher =
            CoordinatorProcessLauncher(
                endpoint = endpoint,
                javaExecutable = "/jdk/bin/java",
                classPath = "client.jar:server.jar",
                idleTimeout = Duration.ofSeconds(7),
            )

        assertEquals(
            listOf(
                "/jdk/bin/java",
                "-cp",
                "client.jar:server.jar",
                "dev.sebastiano.spectre.input.server.CoordinatorProcessMainKt",
                "--socket",
                endpoint.socketPath.toString(),
                "--idle-millis",
                "7000",
            ),
            launcher.command(),
        )
    }

    @Test
    fun `forked coordinator accepts a real client lease`() {
        val launcher =
            CoordinatorProcessLauncher(endpoint = endpoint, idleTimeout = Duration.ofSeconds(5))
        process = launcher.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var acquired = false

        while (!acquired && System.nanoTime() < deadline) {
            runCatching {
                    LocalInputCoordinatorClient.connect(
                            endpoint,
                            DesktopResourceKey("user:501/macos-console"),
                            "forked test",
                        )
                        .use { client ->
                            client.acquire(Duration.ofSeconds(1), "click").use { acquired = true }
                        }
                }
                .onFailure { Thread.onSpinWait() }
        }

        assertTrue(acquired, "Forked coordinator did not accept a lease before the deadline")
    }
}

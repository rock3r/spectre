package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.cli.SpectreCli
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonClientTest {
    @Test
    fun `operation exceptions preserve diagnostics without leaking them to later requests`() {
        val failures =
            ArrayDeque<RuntimeException>().apply {
                add(SecurityException("OS discovery denied"))
                add(IllegalArgumentException("invalid VM identifier"))
            }
        val socketPath = temporaryDaemonClientSocketPath()
        val registry = DaemonSessionRegistry {
            val failure = failures.removeFirst()
            RequestDiagnostics.report("attach warning: ${failure.message}")
            throw failure
        }
        val server = DaemonServer(socketPath, registry)
        val client = DaemonClient(socketPath, retainDiagnostics = true)

        try {
            listOf(
                    SecurityException("OS discovery denied"),
                    IllegalArgumentException("invalid VM identifier"),
                )
                .forEachIndexed { index, failure ->
                    assertEquals(
                        DaemonResponse.Diagnosed(
                            response =
                                DaemonResponse.Error(
                                    DaemonErrorCode.OperationFailed,
                                    "${failure.javaClass.simpleName}: ${failure.message}",
                                ),
                            warnings = listOf("attach warning: ${failure.message}"),
                        ),
                        client.request(DaemonRequest.Attach(index.toLong() + 1)),
                    )
                }

            assertEquals(
                DaemonResponse.Sessions(emptyList()),
                client.request(DaemonRequest.ListSessions),
            )
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `public CLI prints hello and operation warnings and fails partial operation`() {
        val property = DaemonProcessLauncher.PROCESS_GROUP_ISOLATED_PROPERTY
        val original = System.getProperty(property)
        val socketPath = temporaryDaemonClientSocketPath()
        System.setProperty(property, "false")
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                windowsResult = {
                    repeat(101) { RequestDiagnostics.report("window warning $it") }
                    RequestDiagnostics.report("x".repeat(5_000))
                    throw java.io.IOException("window provider failed")
                }
            )
        }
        val server = DaemonServer(socketPath, registry)
        val output = StringBuilder()
        val errorOutput = StringBuilder()

        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(91))
            )
            val exit = SpectreCli(output, errorOutput, socketPath).run(listOf("windows", "pid-91"))

            assertNotEquals(0, exit)
            val lines = errorOutput.lines().filter(String::isNotEmpty)
            assertEquals(103, lines.size, errorOutput.toString())
            assertTrue(
                lines.single { it.contains("independent process group") }.startsWith("warning: ")
            )
            assertEquals(
                (0..98).map { "warning: window warning $it" },
                lines.filter { it.startsWith("warning: window warning") },
            )
            assertEquals(
                "warning: [spectre-daemon] diagnostics truncated (discarded 3 warning(s) and " +
                    "904 warning character(s)); recent diagnostics may remain in the retained " +
                    "suffix of the per-socket daemon log",
                lines[100],
            )
            assertEquals(1, lines.count { it.contains("window provider failed") })
        } finally {
            if (original == null) System.clearProperty(property)
            else System.setProperty(property, original)
            server.close()
            server.awaitTermination()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `hello process isolation warning reaches the public client warning sink`() {
        val property = DaemonProcessLauncher.PROCESS_GROUP_ISOLATED_PROPERTY
        val original = System.getProperty(property)
        val socketPath = temporaryDaemonClientSocketPath()
        System.setProperty(property, "false")
        val server = DaemonServer(socketPath, registry = testDaemonSessionRegistry())
        val warnings = mutableListOf<String>()

        try {
            assertEquals(
                DaemonResponse.Sessions(emptyList()),
                DaemonClient(socketPath, warningSink = warnings::add)
                    .request(DaemonRequest.ListSessions),
            )
            assertTrue(warnings.single().contains("independent process group"))
        } finally {
            if (original == null) System.clearProperty(property)
            else System.setProperty(property, original)
            server.close()
            server.awaitTermination()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `configuration mismatch distinguishes an unset value from a missing key`() {
        val property = "java.io.tmpdir"

        val message = configurationMismatchFailure(mapOf(property to null), emptyMap())

        assertTrue(message.contains("caller=<unset>"), message)
        assertTrue(message.contains("daemon=<missing>"), message)
    }

    @Test
    fun `daemon handshake reports startup configuration after runtime installs embedded agent`() {
        val property = "dev.sebastiano.spectre.agent.runtimeJar"
        val original = System.getProperty(property)
        val socketPath = temporaryDaemonClientSocketPath()
        System.clearProperty(property)
        val server = DaemonServer(socketPath, registry = testDaemonSessionRegistry())

        try {
            System.setProperty(property, "/tmp/extracted-agent-runtime.jar")
            val failure =
                assertFailsWith<java.io.IOException> {
                    DaemonClient(socketPath).request(DaemonRequest.ListSessions)
                }
            assertTrue(failure.message.orEmpty().contains(property))
        } finally {
            if (original == null) System.clearProperty(property)
            else System.setProperty(property, original)
            server.close()
            server.awaitTermination()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `explains how to replace a daemon that rejects a newer hello`() {
        assertEquals(
            "Spectre daemon does not support this command. Run `spectre daemon kill` and retry.",
            daemonHandshakeFailure(
                required = DaemonProtocolVersion(major = 1, minor = 4),
                response =
                    DaemonResponse.Error(
                        code = DaemonErrorCode.ProtocolError,
                        message = "incompatible daemon protocol version",
                    ),
            ),
        )
    }

    @Test
    fun `explains how to replace a daemon too old for a request`() {
        assertEquals(
            "Spectre daemon protocol 1.3 is too old for this command. " +
                "Run `spectre daemon kill` and retry.",
            daemonCompatibilityFailure(
                required = DaemonProtocolVersion(major = 1, minor = 4),
                daemon = DaemonProtocolVersion(major = 1, minor = 3),
            ),
        )
    }

    @Test
    fun `daemon runtime classpath includes the loadable agent runtime`() {
        val runtimeEntries = testRuntimeClassPath().split(File.pathSeparator)

        assertTrue(
            runtimeEntries.any { entry ->
                File(entry).name.startsWith("agent-runtime-") && entry.endsWith(".jar")
            }
        )
    }

    @Test
    fun `starts a detached daemon process for the first request`() {
        val socketPath = temporaryDaemonClientSocketPath()
        var process: Process? = null

        try {
            DaemonClient(socketPath).use { client ->
                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    client.requestOrStart(DaemonRequest.ListSessions) {
                        process =
                            DaemonProcessLauncher(
                                    socketPath = socketPath,
                                    classPath = testRuntimeClassPath(),
                                )
                                .start()
                    },
                )
                assertEquals(DaemonResponse.ShuttingDown, client.request(DaemonRequest.Shutdown))
            }

            awaitDaemonClientSocketRemoval(socketPath)
            assertDaemonExited(requireNotNull(process))
        } finally {
            process?.takeIf(Process::isAlive)?.destroyForcibly()?.waitFor()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `starts the daemon in its own process group so reaping the caller's group spares it`() {
        // #557: tool runners kill a finished command's whole process group. A daemon left in the
        // CLI's group dies with it, taking every attached session along.
        val socketPath = temporaryDaemonClientSocketPath()
        var process: Process? = null
        var shutdownConfirmed = false

        try {
            DaemonClient(socketPath).use { client ->
                client.requestOrStart(DaemonRequest.ListSessions) {
                    process =
                        DaemonProcessLauncher(
                                socketPath = socketPath,
                                classPath = testRuntimeClassPath(),
                            )
                            .start()
                }
                val daemonPid = requireNotNull(process).pid()

                assertEquals(
                    daemonPid,
                    processGroupOf(daemonPid),
                    "daemon should lead its own process group",
                )
                assertNotEquals(processGroupOf(ProcessHandle.current().pid()), daemonPid)

                assertEquals(DaemonResponse.ShuttingDown, client.request(DaemonRequest.Shutdown))
                shutdownConfirmed = true
            }

            awaitDaemonClientSocketRemoval(socketPath)
        } finally {
            if (!shutdownConfirmed) process?.destroyForcibly()?.waitFor()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `restarts a daemon after its process is killed`() {
        val socketPath = temporaryDaemonClientSocketPath()
        val processes = mutableListOf<Process>()

        try {
            DaemonClient(socketPath).use { client ->
                fun startDaemon() {
                    processes +=
                        DaemonProcessLauncher(
                                socketPath = socketPath,
                                classPath = testRuntimeClassPath(),
                            )
                            .start()
                }

                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    client.requestOrStart(DaemonRequest.ListSessions, ::startDaemon),
                )
                processes.single().destroyForcibly().waitFor()

                assertTrue(Files.exists(socketPath), "Killed daemon should leave a stale socket")
                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    client.requestOrStart(DaemonRequest.ListSessions, ::startDaemon),
                )
                assertEquals(2, processes.size)
                assertEquals(DaemonResponse.ShuttingDown, client.request(DaemonRequest.Shutdown))
            }

            awaitDaemonClientSocketRemoval(socketPath)
            assertDaemonExited(processes.last())
        } finally {
            processes.filter(Process::isAlive).forEach { it.destroyForcibly().waitFor() }
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `starts the daemon then sends the first request when the socket is absent`() {
        val socketPath = temporaryDaemonClientSocketPath()
        var server: DaemonServer? = null
        var starts = 0

        try {
            DaemonClient(socketPath).use { client ->
                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    client.requestOrStart(DaemonRequest.ListSessions) {
                        starts++
                        server = DaemonServer(socketPath)
                    },
                )
            }

            assertEquals(1, starts)
        } finally {
            server?.close()
            server?.awaitTermination()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `sends a handshake before forwarding requests to the daemon`() {
        val socketPath = temporaryDaemonClientSocketPath()
        val server = DaemonServer(socketPath, registry = testDaemonSessionRegistry())

        try {
            DaemonClient(socketPath).use { client ->
                assertEquals(
                    DaemonResponse.Attached(sessionId = "pid-1234", targetPid = 1234),
                    client.request(DaemonRequest.Attach(1234)),
                )
                assertEquals(
                    DaemonResponse.Sessions(
                        sessions =
                            listOf(DaemonSessionSummary(sessionId = "pid-1234", targetPid = 1234))
                    ),
                    client.request(DaemonRequest.ListSessions),
                )
            }
        } finally {
            server.close()
            server.awaitTermination()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }
}

internal fun temporaryDaemonClientSocketPath(): Path =
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Path.of("/tmp", "sp-d-${UUID.randomUUID().toString().take(8)}", "daemon", "daemon.sock")
    } else {
        Files.createTempDirectory("spectre-daemon-test").resolve("daemon").resolve("daemon.sock")
    }

internal fun deleteTemporaryDaemonClientSocketPath(socketPath: Path) {
    Files.deleteIfExists(socketPath)
    Files.deleteIfExists(DaemonProcessLauncher.diagnosticPath(socketPath))
    Files.deleteIfExists(socketPath.parent)
    Files.deleteIfExists(socketPath.parent.parent)
}

private fun awaitDaemonClientSocketRemoval(socketPath: Path) {
    repeat(MAX_SOCKET_REMOVAL_ATTEMPTS) {
        if (!Files.exists(socketPath)) return
        Thread.sleep(SOCKET_REMOVAL_WAIT_MILLIS)
    }
    error("Timed out waiting for daemon socket removal")
}

private fun assertDaemonExited(process: Process) {
    assertTrue(
        process.waitFor(DAEMON_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "Daemon JVM did not exit within $DAEMON_EXIT_TIMEOUT_SECONDS seconds after shutdown",
    )
}

/** Reads a process's group id with `ps`, which Linux and macOS both ship. */
private fun processGroupOf(pid: Long): Long {
    val ps = ProcessBuilder("ps", "-o", "pgid=", "-p", pid.toString()).start()
    val output = ps.inputStream.bufferedReader().readText().trim()
    check(ps.waitFor() == 0) { "ps failed for pid $pid: $output" }
    return output.toLong()
}

private const val MAX_SOCKET_REMOVAL_ATTEMPTS: Int = 100
private const val SOCKET_REMOVAL_WAIT_MILLIS: Long = 10
private const val DAEMON_EXIT_TIMEOUT_SECONDS: Long = 10

private fun testRuntimeClassPath(): String =
    requireNotNull(System.getProperty("spectre.cli.testRuntimeClasspath")) {
        "Missing CLI test runtime classpath"
    }

@OptIn(ExperimentalSpectreAgentApi::class)
private fun testDaemonSessionRegistry(): DaemonSessionRegistry = DaemonSessionRegistry {
    TestDaemonSessionAutomator()
}

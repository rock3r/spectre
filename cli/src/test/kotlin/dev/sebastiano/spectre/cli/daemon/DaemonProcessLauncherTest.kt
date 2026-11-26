package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class DaemonProcessLauncherTest {
    @Test
    fun `concurrent first launches create a private parent and persistent log`() {
        val socket = temporaryDaemonClientSocketPath()
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val launches =
                List(4) {
                    pool.submit<Int> {
                        start.await()
                        probeLauncher(socket).start().waitFor()
                    }
                }
            start.countDown()
            launches.forEach { assertEquals(0, it.get(30, TimeUnit.SECONDS)) }
            val log = DaemonProcessLauncher.diagnosticPath(socket)
            assertPrivate(socket.parent, "rwx------")
            assertPrivate(log, "rw-------")
            assertTrue(Files.size(log) > 0)
        } finally {
            pool.shutdownNow()
            deleteTemporaryDaemonClientSocketPath(socket)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `launcher rejects a caller-owned readable log without modifying it`() {
        val socket = temporaryDaemonClientSocketPath()
        try {
            probeLauncher(socket).start().waitFor()
            val log = DaemonProcessLauncher.diagnosticPath(socket)
            val original = Files.readString(log)
            val permissions = PosixFilePermissions.fromString("rw-r--r--")
            Files.setPosixFilePermissions(log, permissions)
            assertFailsWith<IOException> { probeLauncher(socket).start().waitFor() }
            assertEquals(permissions, Files.getPosixFilePermissions(log))
            assertEquals(original, Files.readString(log))
        } finally {
            deleteTemporaryDaemonClientSocketPath(socket)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `launcher rejects an existing noncanonical log ACL without rewriting it`() {
        val socket = temporaryDaemonClientSocketPath()
        try {
            probeLauncher(socket).start().waitFor()
            val log = DaemonProcessLauncher.diagnosticPath(socket)
            val view = Files.getFileAttributeView(log, AclFileAttributeView::class.java)
            val entry = view.acl.single()
            val limited =
                AclEntry.newBuilder(entry)
                    .setPermissions(entry.permissions() - AclEntryPermission.WRITE_ATTRIBUTES)
                    .build()
            view.acl = listOf(limited)
            assertFailsWith<IOException> { probeLauncher(socket).start().waitFor() }
            assertEquals(listOf(limited), view.acl)
        } finally {
            deleteTemporaryDaemonClientSocketPath(socket)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `launcher leaves a symbolic link log target untouched`() {
        val socket = temporaryDaemonClientSocketPath()
        val target = socket.parent.resolve("target")
        try {
            probeLauncher(socket).start().waitFor()
            val log = DaemonProcessLauncher.diagnosticPath(socket)
            Files.move(log, target)
            val original = Files.readString(target)
            Files.createSymbolicLink(log, target)
            assertFailsWith<IOException> { probeLauncher(socket).start().waitFor() }
            assertEquals(original, Files.readString(target))
        } finally {
            Files.deleteIfExists(target)
            deleteTemporaryDaemonClientSocketPath(socket)
        }
    }

    private fun probeLauncher(socket: Path) =
        DaemonProcessLauncher(
            socket,
            aotJvmArguments = { listOf("-version") },
            newSessionPrefix = { emptyList() },
        )

    private fun assertPrivate(path: Path, posixMode: String) {
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            assertEquals(
                PosixFilePermissions.fromString(posixMode),
                Files.getPosixFilePermissions(path),
            )
        } else {
            val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
            val entry = view.acl.single()
            assertEquals(AclEntryType.ALLOW, entry.type())
            assertEquals(view.owner, entry.principal())
            assertEquals(AclEntryPermission.values().toSet(), entry.permissions())
        }
    }

    @Test
    fun `builds a daemon-only Java command for its socket`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    // No forwarded -D properties: the real defaults resolve
                    // user.home and java.io.tmpdir on any host, which would
                    // make the expected command host-dependent.
                    readProperty = { null },
                    callerJvmOptions = { emptyList() },
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

    @Test
    fun `includes ready AOT cache arguments for the daemon JVM`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    aotJvmArguments = { listOf("-XX:AOTCache=/tmp/aot/spectre.aot") },
                    readProperty = { null },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf(
                "/jdk/bin/java",
                "-XX:AOTCache=/tmp/aot/spectre.aot",
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
                    callerJvmOptions = { emptyList() },
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
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf("-Ddev.sebastiano.spectre.agent.runtimeJar=/jars/agent.jar"),
            command.filter { it.startsWith("-D") },
        )
    }

    @Test
    fun `forwards standard properties that shape the daemon's JVM state`() {
        // Regression for #551: the daemon is a fresh JVM launched from an
        // explicit argument list, so a standard property a caller sets on the
        // CLI launch never reaches it unless it is restated here. The daemon
        // reads JVM state these shape — the temp and home directories its
        // captures and runtime files land under, and headless mode for
        // Robot-driven input — so a caller's override silently stopping at
        // the CLI means captures in the wrong place or failures in a sandbox
        // whose default home is unwritable.
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { name ->
                        mapOf(
                            "java.io.tmpdir" to "/custom-tmp",
                            "user.home" to "/writable-home",
                            "java.awt.headless" to "false",
                        )[name]
                    },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(
            listOf(
                "-Djava.io.tmpdir=/custom-tmp",
                "-Duser.home=/writable-home",
                "-Djava.awt.headless=false",
            ),
            command.filter { it.startsWith("-D") },
        )
    }

    @Test
    fun `forwards caller JVM options to the daemon`() {
        // JVM options the caller gave the CLI (via JAVA_TOOL_OPTIONS & friends, or
        // JAVA_OPTS/SPECTRE_OPTS) are reconstructed onto the CLI's own command line
        // by the bootstrap; the daemon is a fresh JVM, so they reach it only if this
        // launcher passes them on — a caller's -Xmx1g must not stop at the
        // short-lived CLI front end. Instrumentation agents are the exception:
        // they belong to the CLI process alone (a JDWP agent would fail to bind
        // its listen address a second time in the daemon).
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    aotJvmArguments = { listOf("-XX:AOTCache=/tmp/aot/spectre.aot") },
                    readProperty = { null },
                    callerJvmOptions = {
                        listOf(
                            "-Xmx1g",
                            "-javaagent:/tmp/agent.jar",
                            "-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
                            "-XX:MaxGCPauseMillis=50",
                        )
                    },
                )
                .command()

        assertEquals(
            listOf(
                "/jdk/bin/java",
                "-XX:AOTCache=/tmp/aot/spectre.aot",
                "-Xmx1g",
                "-XX:MaxGCPauseMillis=50",
                "-cp",
                "spectre.jar",
                "dev.sebastiano.spectre.cli.daemon.DaemonMainKt",
                "--socket",
                Path.of("/tmp", "spectre", "daemon.sock").toString(),
            ),
            command,
        )
    }

    @Test
    fun `daemon environment drops inherited JVM option variables`() {
        // The daemon's options are reconstructed explicitly on its command line;
        // leaving JAVA_TOOL_OPTIONS & friends in its environment would apply
        // them a second time (agents twice, or an env collector colliding with
        // the cache's pinned G1). Thin start-script launches keep those
        // variables in the CLI's environment, so the scrub must happen here.
        val environment =
            mutableMapOf(
                "JAVA_TOOL_OPTIONS" to "-XX:+UseZGC",
                "JDK_JAVA_OPTIONS" to "-Xmx1g",
                "_JAVA_OPTIONS" to "-Dfoo=bar",
                "PATH" to "/usr/bin",
            )

        DaemonProcessLauncher.sanitizeDaemonEnvironment(environment)

        assertEquals(mapOf("PATH" to "/usr/bin"), environment)
    }

    @Test
    fun `forwards nothing when nothing is set`() {
        val command =
            DaemonProcessLauncher(
                    socketPath = Path.of("/tmp/spectre/daemon.sock"),
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { null },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(emptyList(), command.filter { it.startsWith("-D") })
    }

    @Test
    fun `launches the daemon command behind the new-session prefix`() {
        val launcher =
            DaemonProcessLauncher(
                socketPath = Path.of("/tmp/spectre/daemon.sock"),
                javaExecutable = "/jdk/bin/java",
                classPath = "spectre.jar",
                readProperty = { null },
                aotJvmArguments = { emptyList() },
                callerJvmOptions = { emptyList() },
                newSessionPrefix = { listOf("/usr/bin/setsid") },
            )

        assertEquals(
            listOf("/usr/bin/setsid", "/jdk/bin/java") +
                "-Ddev.sebastiano.spectre.daemon.processGroupIsolated=true" +
                launcher.command().drop(1),
            launcher.processBuilder().command(),
        )
    }

    @Test
    fun `starts the daemon through setsid on Linux`() {
        assertEquals(
            listOf("/usr/bin/setsid"),
            DaemonProcessLauncher.newSessionPrefixFor("Linux") { it == "/usr/bin/setsid" },
        )
        assertEquals(
            listOf("/bin/setsid"),
            DaemonProcessLauncher.newSessionPrefixFor("Linux") { it == "/bin/setsid" },
        )
    }

    @Test
    fun `starts the daemon through perl's setsid where no setsid command exists`() {
        // macOS ships no setsid(1), but does ship perl, whose POSIX::setsid then exec keeps the
        // daemon's pid, so the launcher's Process handle still tracks the daemon itself.
        val prefix = DaemonProcessLauncher.newSessionPrefixFor("Mac OS X") { it == "/usr/bin/perl" }

        assertEquals("/usr/bin/perl", prefix.first())
        assertEquals("--", prefix.last())
        assertTrue(prefix.any { "POSIX::setsid()" in it && "exec" in it })
    }

    @Test
    fun `launches the daemon directly on Windows or when no new-session tool exists`() {
        assertEquals(emptyList(), DaemonProcessLauncher.newSessionPrefixFor("Windows 11") { true })
        assertEquals(emptyList(), DaemonProcessLauncher.newSessionPrefixFor("Linux") { false })
    }
}

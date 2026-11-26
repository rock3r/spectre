@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.launch.LaunchAgentBootstrapException
import dev.sebastiano.spectre.agent.launch.LaunchReadiness
import dev.sebastiano.spectre.agent.launch.LaunchStage
import dev.sebastiano.spectre.agent.launch.ProcessExitedBeforeAttachException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.WatchService
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class WindowsAttachHelperTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `codec preserves attach-not-supported type for launch classification`() {
        val failure =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AttachNotSupportedException", "not attachable")
                )
            )

        assertEquals(
            RemoteAttachFailureKind.AttachNotSupported,
            (failure.cause as RemoteAttachFailure).kind,
        )
        assertTrue(LaunchReadiness.isNoLiveAgentFailure(failure))
    }

    @Test
    fun `codec preserves agent-load type and launch classification uses its detail`() {
        val dying =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AgentLoadException", "Target VM did not respond")
                )
            )
        val ordinary =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AgentLoadException", "agent library failed to init")
                )
            )

        assertEquals(RemoteAttachFailureKind.AgentLoad, (dying.cause as RemoteAttachFailure).kind)
        assertTrue(LaunchReadiness.isNoLiveAgentFailure(dying))
        assertEquals(
            RemoteAttachFailureKind.AgentLoad,
            (ordinary.cause as RemoteAttachFailure).kind,
        )
        assertFalse(LaunchReadiness.isNoLiveAgentFailure(ordinary))
    }

    @Test
    fun `codec retains unknown helper causes without granting launch classification`() {
        val failure =
            requireNotNull(WindowsAttachHelper.failure(response("UnrelatedException", "detail")))

        assertEquals("UnrelatedException: detail", failure.cause?.message)
        assertFalse(LaunchReadiness.isNoLiveAgentFailure(failure))
    }

    @Test
    fun `malformed encoded response is reported rather than escaping the parser`() {
        val failure = requireNotNull(WindowsAttachHelper.failure("ERROR\t%%%\t%%%\t%%%"))

        assertTrue(failure.message.orEmpty().contains("Invalid Windows attach helper response"))
    }

    @Test
    fun `diagnostics and launch classification work without jdk attach module`() {
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--limit-modules",
                    "java.base",
                    "-cp",
                    System.getProperty("java.class.path"),
                    WindowsAttachWithoutModuleFixture::class.java.name,
                )
                .start()

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "limited-module child did not terminate")
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), "stdout=$stdout\nstderr=$stderr")
        assertEquals("attach-without-module-ok", stdout.trim())
    }

    @Test
    fun `typed diagnostic preserves remaining deadline for real process exit and output`() {
        val stdout = directory.resolve("typed-exit.out")
        val stderr = directory.resolve("typed-exit.err")
        val ready = directory.resolve("typed-exit.ready")
        val readyEvents = directory.fileSystem.newWatchService()
        directory.register(readyEvents, ENTRY_CREATE)
        val process =
            ProcessBuilder(childCommand(ready))
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start()
        val monitoredProcess =
            ReleaseOnTimedWaitProcess(process) {
                process.outputStream.use { it.write(EXIT_RELEASE) }
            }
        val failure =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AttachNotSupportedException", "Target VM did not respond")
                )
            )

        try {
            awaitReady(ready, readyEvents)
            assertFailsWith<LaunchAgentBootstrapException> {
                LaunchReadiness.bootstrapFailureOrProcessExit(
                    process = monitoredProcess,
                    gradleish = false,
                    attachedPid = process.pid(),
                    stdoutPath = stdout,
                    stderrPath = stderr,
                    cause = failure,
                    graceMs = 0,
                )
            }
            assertTrue(process.isAlive, "zero grace must not release the child")

            val ex =
                assertFailsWith<ProcessExitedBeforeAttachException> {
                    LaunchReadiness.bootstrapFailureOrProcessExit(
                        process = monitoredProcess,
                        gradleish = false,
                        attachedPid = process.pid(),
                        stdoutPath = stdout,
                        stderrPath = stderr,
                        cause = failure,
                        graceMs = LaunchReadiness.exitGraceMs(2_000L, failure),
                    )
                }

            assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
            assertEquals(17, ex.exitCode)
            assertEquals("before-exit", Files.readString(ex.stdoutPath))
            assertTrue(ex.stderrExcerpt.contains("exit-detail"))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "child JVM did not terminate")
            }
            readyEvents.close()
        }
    }

    @Test
    fun `helper launches when application class path does not contain Spectre`() {
        val original = System.getProperty("java.class.path")
        val missing = Path.of(System.getProperty("java.io.tmpdir"), "not-a-spectre-classpath")
        try {
            System.setProperty("java.class.path", missing.toString())
            val process =
                ProcessBuilder(WindowsAttachHelper.command(Long.MAX_VALUE, missing, "args")).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(2, process.exitValue(), output)
            assertNotNull(WindowsAttachHelper.failure(output))
        } finally {
            System.setProperty("java.class.path", original)
        }
    }

    private fun childCommand(ready: Path) =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            WindowsAttachHelperExitFixture::class.java.name,
            ready.toString(),
        )

    private fun awaitReady(ready: Path, events: WatchService) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!Files.exists(ready)) {
            val remaining = deadline - System.nanoTime()
            assertTrue(remaining > 0, "child JVM did not become ready")
            val key = events.poll(remaining, TimeUnit.NANOSECONDS)
            assertNotNull(key, "child JVM did not become ready")
            key.pollEvents()
            assertTrue(key.reset(), "child JVM ready watch became invalid")
        }
    }

    private fun response(type: String, detail: String): String =
        listOf("ERROR", encode("attach failed"), encode(type), encode(detail)).joinToString("\t")

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private companion object {
        const val EXIT_RELEASE: Int = 1
    }
}

object WindowsAttachHelperExitFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        print("before-exit")
        System.out.flush()
        System.err.print("exit-detail")
        System.err.flush()
        Files.createFile(Path.of(args.single()))
        check(System.`in`.read() >= 0) { "parent closed stdin before exit release" }
        kotlin.system.exitProcess(17)
    }
}

object WindowsAttachWithoutModuleFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(value: String) = encoder.encodeToString(value.toByteArray())
        fun response(type: String, detail: String) =
            listOf("ERROR", encode("attach failed"), encode(type), encode(detail))
                .joinToString("\t")

        val attach =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AttachNotSupportedException", "not attachable")
                )
            )
        check(attach.cause?.message == "not attachable")
        check(LaunchReadiness.isNoLiveAgentFailure(attach))

        val dyingLoad =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AgentLoadException", "Target VM did not respond")
                )
            )
        check(LaunchReadiness.isNoLiveAgentFailure(dyingLoad))
        val ordinaryLoad =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AgentLoadException", "agent library failed to init")
                )
            )
        check(!LaunchReadiness.isNoLiveAgentFailure(ordinaryLoad))
        val unknown =
            requireNotNull(WindowsAttachHelper.failure(response("UnrelatedException", "detail")))
        check(!LaunchReadiness.isNoLiveAgentFailure(unknown))
        check(!LaunchReadiness.isNoLiveAgentFailure(IllegalStateException("ordinary")))
        check(LaunchReadiness.isNoLiveAgentFailure(AttachNotSupportedException()))
        check(
            LaunchReadiness.isNoLiveAgentFailure(
                AgentLoadException("target process not responding")
            )
        )
        check(!LaunchReadiness.isNoLiveAgentFailure(AgentLoadException("invalid agent")))

        val bootstrap = runCatching {
            LaunchReadiness.bootstrapFailureOrProcessExit(
                process = UnusedProcess,
                gradleish = true,
                attachedPid = 42,
                stdoutPath = Path.of("stdout"),
                stderrPath = Path.of("stderr"),
                cause = ordinaryLoad,
                graceMs = 0,
            )
        }
            .exceptionOrNull()
        check(bootstrap is LaunchAgentBootstrapException)
        check(bootstrap.cause === ordinaryLoad)
        check(bootstrap.cause?.cause?.message == "agent library failed to init")

        print("attach-without-module-ok")
    }
}

private class AttachNotSupportedException : RuntimeException()

private class AgentLoadException(message: String) : RuntimeException(message)

private class ReleaseOnTimedWaitProcess(
    private val delegate: Process,
    private val release: () -> Unit,
) : Process() {
    private var released = false

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getErrorStream(): InputStream = delegate.errorStream

    override fun waitFor(): Int {
        releaseOnce()
        return delegate.waitFor()
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        if (timeout > 0) releaseOnce()
        return delegate.waitFor(timeout, unit)
    }

    override fun exitValue() = delegate.exitValue()

    override fun destroy() = delegate.destroy()

    override fun isAlive() = delegate.isAlive

    private fun releaseOnce() {
        if (!released) {
            released = true
            release()
        }
    }
}

private object UnusedProcess : Process() {
    override fun getOutputStream() = error("unused")

    override fun getInputStream() = error("unused")

    override fun getErrorStream() = error("unused")

    override fun waitFor(): Int = error("unused")

    override fun exitValue(): Int = error("unused")

    override fun destroy() = error("unused")
}

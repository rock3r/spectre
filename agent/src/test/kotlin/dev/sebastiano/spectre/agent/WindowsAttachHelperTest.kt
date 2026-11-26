@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.launch.LaunchReadiness
import dev.sebastiano.spectre.agent.launch.LaunchStage
import dev.sebastiano.spectre.agent.launch.ProcessExitedBeforeAttachException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

        assertIs<com.sun.tools.attach.AttachNotSupportedException>(failure.cause)
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

        assertIs<com.sun.tools.attach.AgentLoadException>(dying.cause)
        assertTrue(LaunchReadiness.isNoLiveAgentFailure(dying))
        assertIs<com.sun.tools.attach.AgentLoadException>(ordinary.cause)
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
    fun `typed diagnostic preserves remaining deadline for real process exit and output`() {
        val stdout = directory.resolve("typed-exit.out")
        val stderr = directory.resolve("typed-exit.err")
        val process =
            ProcessBuilder(childCommand())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start()
        val failure =
            requireNotNull(
                WindowsAttachHelper.failure(
                    response("AttachNotSupportedException", "Target VM did not respond")
                )
            )

        try {
            val ex =
                assertFailsWith<ProcessExitedBeforeAttachException> {
                    LaunchReadiness.bootstrapFailureOrProcessExit(
                        process = process,
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

    private fun childCommand() =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            WindowsAttachHelperExitFixture::class.java.name,
        )

    private fun response(type: String, detail: String): String =
        listOf("ERROR", encode("attach failed"), encode(type), encode(detail)).joinToString("\t")

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}

object WindowsAttachHelperExitFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        print("before-exit")
        System.out.flush()
        System.err.print("exit-detail")
        System.err.flush()
        Thread.sleep(200)
        kotlin.system.exitProcess(17)
    }
}

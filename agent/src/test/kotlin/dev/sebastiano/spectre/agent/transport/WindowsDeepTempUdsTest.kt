@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.AttachOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * End-to-end regression for #442 on the platform that had the bug.
 *
 * `AttachOptionsTest` pins the *selection* logic as pure strings, which runs everywhere. This test
 * closes the remaining gap: given a `java.io.tmpdir` as deep as the one Bazel hands a Windows test,
 * the fallback base is a real directory on this machine, and a `ServerSocketChannel` actually binds
 * there and serves a request. Nothing but a Windows host can prove that.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsDeepTempUdsTest {
    private var boundPath: Path? = null

    @AfterTest
    fun cleanUp() {
        boundPath?.let { path ->
            runCatching { path.deleteIfExists() }
            runCatching { path.parent?.deleteIfExists() }
        }
    }

    @Test
    fun `a Bazel-deep java_io_tmpdir falls back to a base that really binds`() {
        // The reporter's TEST_TMPDIR, verbatim from #442: 83 characters, which pushed
        // <tmp>\sp-a-<pid>-<uuid>\agent.sock to 115 bytes against the ~108-byte sun_path cap.
        val bazelTmpDir =
            "C:\\programdata\\_bazel\\vn2ifhp6\\execroot\\_main\\_tmp\\5c46559f6d626ed6fb8fd5728fe0401e"
        val perAttachDir = "sp-a-103560-a1b2c3d4"

        val candidates =
            AttachOptions.udsBaseDirCandidates(
                osName = System.getProperty("os.name").orEmpty(),
                tmpDir = bazelTmpDir,
                localAppData = System.getenv("LOCALAPPDATA"),
                userHome = System.getProperty("user.home").orEmpty(),
            )
        val udsPath = AttachOptions.selectUdsPath(candidates, perAttachDir)
        boundPath = udsPath

        assertTrue(
            candidates.first() == bazelTmpDir,
            "the harness temp dir should still be tried first, got $candidates",
        )
        assertFalse(
            udsPath.startsWith(Path.of(bazelTmpDir)),
            "the deep harness temp dir should have been skipped, got $udsPath",
        )
        assertFalse(
            UdsPathLimits.exceedsLimit(udsPath),
            "selected path is ${UdsPathLimits.byteLength(udsPath)} bytes, over the " +
                "${UdsPathLimits.maxPathBytes}-byte limit: $udsPath",
        )

        // The real proof: Windows accepts this path for an AF_UNIX bind and the socket serves.
        IpcServer(udsPath, pingOnlyHandler()).use {
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
            }
        }
    }

    private fun pingOnlyHandler(): AgentRequestHandler = AgentRequestHandler { request ->
        when (request) {
            AgentRequest.Ping -> AgentResponse.Pong
            else -> AgentResponse.Error(message = "unsupported in this test")
        }
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS file $path did not appear within ${timeoutMs} ms")
    }
}

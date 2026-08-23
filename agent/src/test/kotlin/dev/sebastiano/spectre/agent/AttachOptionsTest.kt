@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.MAX_FRAME_BYTES_CEILING
import dev.sebastiano.spectre.agent.transport.UdsPathLimits
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachOptionsTest {

    // ---- frame budget validation ----
    //
    // A budget the target cannot apply must fail at the caller, not in the injected JVM. The agent
    // logs and ignores a bad value so a tuning mistake cannot break the attach, which means an
    // unvalidated option would let `attach()` report success while the target silently kept its own
    // budget and later rejected captures the caller sized for.

    @Test
    fun `a budget above the read ceiling is rejected at construction`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AttachOptions(maxFrameBytes = MAX_FRAME_BYTES_CEILING + 1)
            }

        assertTrue(failure.message.orEmpty().contains("ceiling", ignoreCase = true), "$failure")
    }

    @Test
    fun `a non-positive budget is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { AttachOptions(maxFrameBytes = 0) }
        assertFailsWith<IllegalArgumentException> { AttachOptions(maxFrameBytes = -1) }
    }

    @Test
    fun `a budget too small for protocol frames is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { AttachOptions(maxFrameBytes = 1) }
    }

    @Test
    fun `a usable budget and the unset default are both accepted`() {
        AttachOptions(maxFrameBytes = MAX_FRAME_BYTES_CEILING)
        AttachOptions(maxFrameBytes = null)
    }

    // ---- base-dir candidates (pure, platform-agnostic) ----

    @Test
    fun `udsBaseDirCandidates offers only slash tmp on POSIX`() {
        assertEquals(
            listOf("/tmp"),
            AttachOptions.udsBaseDirCandidates(
                osName = "Linux",
                tmpDir = "/var/folders/ignored",
                localAppData = null,
                userHome = "/home/x",
            ),
        )
        assertEquals(
            listOf("/tmp"),
            AttachOptions.udsBaseDirCandidates(
                osName = "Mac OS X",
                tmpDir = "/var/folders/ignored",
                localAppData = null,
                userHome = "/Users/x",
            ),
        )
    }

    @Test
    fun `udsBaseDirCandidates prefers the JVM temp dir on Windows, then per-user fallbacks`() {
        // %LOCALAPPDATA%\Temp and <user.home>\AppData\Local\Temp resolve to the same directory
        // on a normal profile, so the duplicate is dropped.
        assertEquals(
            listOf("C:\\bazel\\_tmp\\5c46559f", "C:\\Users\\x\\AppData\\Local\\Temp"),
            AttachOptions.udsBaseDirCandidates(
                osName = "Windows 11",
                tmpDir = "C:\\bazel\\_tmp\\5c46559f",
                localAppData = "C:\\Users\\x\\AppData\\Local",
                userHome = "C:\\Users\\x",
            ),
        )
    }

    @Test
    fun `udsBaseDirCandidates skips blank or missing Windows candidates`() {
        assertEquals(
            listOf("C:\\Users\\x\\AppData\\Local\\Temp"),
            AttachOptions.udsBaseDirCandidates(
                osName = "Windows 10",
                tmpDir = "  ",
                localAppData = null,
                userHome = "C:\\Users\\x",
            ),
        )
    }

    // ---- budget-aware selection (issue #442) ----

    @Test
    fun `selectUdsPath skips a base whose resulting path would overflow sun_path`() {
        // Mirrors the Bazel-on-Windows report in #442: java.io.tmpdir is deep enough that
        // <tmp>/sp-a-<pid>-<uuid>/agent.sock passes the sun_path cap, so the next candidate wins.
        val deepBase = "/deep/" + "d".repeat(100)
        val perAttachDir = "sp-a-103560-a1b2c3d4"

        val selected = AttachOptions.selectUdsPath(listOf(deepBase, "/tmp"), perAttachDir)

        assertEquals(Path.of("/tmp", perAttachDir, "agent.sock"), selected)
    }

    @Test
    fun `selectUdsPath keeps the first candidate when it fits`() {
        val perAttachDir = "sp-a-1234-a1b2c3d4"

        val selected = AttachOptions.selectUdsPath(listOf("/tmp", "/other"), perAttachDir)

        assertEquals(Path.of("/tmp", perAttachDir, "agent.sock"), selected)
    }

    @Test
    fun `selectUdsPath fails with an actionable message when no candidate fits`() {
        val deepBase = "/deep/" + "d".repeat(120)

        val ex =
            assertFailsWith<UdsPathTooLongException> {
                AttachOptions.selectUdsPath(listOf(deepBase), "sp-a-1234-a1b2c3d4")
            }

        val message = ex.message.orEmpty()
        assertTrue(deepBase in message, "message should name the candidate, got: $message")
        assertTrue(
            "${UdsPathLimits.maxPathBytes}" in message,
            "message should name the sun_path limit, got: $message",
        )
        assertTrue(
            "udsPath" in message,
            "message should point at the AttachOptions.udsPath escape hatch, got: $message",
        )
    }

    // ---- default path structure (platform-agnostic) ----

    @Test
    fun `defaultUdsPath ends in the per-attach dir plus agent socket`() {
        val p = AttachOptions.defaultUdsPath(1234L)
        assertEquals("agent.sock", p.fileName.toString())
        assertTrue(
            p.parent.fileName.toString().startsWith("sp-a-1234-"),
            "per-attach dir should be sp-a-<pid>-<uuid>, got ${p.parent.fileName}",
        )
    }

    @Test
    fun `defaultUdsPath always fits the sun_path budget`() {
        // Regression for #442: the default must never hand the agent a path the OS will reject,
        // whatever the harness set java.io.tmpdir to.
        val p = AttachOptions.defaultUdsPath(Long.MAX_VALUE)
        assertFalse(
            UdsPathLimits.exceedsLimit(p),
            "default UDS path is ${UdsPathLimits.byteLength(p)} bytes, over the " +
                "${UdsPathLimits.maxPathBytes}-byte limit: $p",
        )
    }

    // ---- real path per platform ----

    @Test
    fun `defaultUdsPath is under one of this platform's base candidates`() {
        val candidates =
            AttachOptions.udsBaseDirCandidates(
                osName = System.getProperty("os.name").orEmpty(),
                tmpDir = System.getProperty("java.io.tmpdir").orEmpty(),
                localAppData = System.getenv("LOCALAPPDATA"),
                userHome = System.getProperty("user.home").orEmpty(),
            )
        val p = AttachOptions.defaultUdsPath(1234L)

        assertTrue(
            candidates.any { p.startsWith(Path.of(it)) },
            "UDS path $p should live under one of $candidates",
        )
    }
}

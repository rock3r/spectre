@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.UdsPathLimits
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Attach-side guard for #442: a UDS path past the `sun_path` cap must fail on the attacher with a
 * message naming the path and the limit, *before* the agent JAR is loaded into the target. Without
 * it the bind failure happens inside the target JVM and the caller only sees "Agent JAR loaded but
 * agent failed to initialize", with the real cause buried in the target's stderr.
 */
class AttachUdsPathLengthTest {

    @Test
    fun `a relative UDS path is resolved against the attacher before it is used`() {
        // The attacher polls `Files.exists(udsPath)` in its own working directory while the target
        // JVM binds in *its* working directory, which is a different one for every launch mode. A
        // relative path therefore means two different files and the attach can only time out.
        // Resolving here makes both sides agree, and makes the length guard above measure the path
        // the target will really bind. Raised as P2 by Codex review on PR #445 — its stated
        // mechanism (the Windows provider resolving before encoding) does not hold, since
        // `WindowsPath.toString()` returns the path as spelled, but the ambiguity is real.
        val relative = Path.of("sp-a-relative", "agent.sock")

        val resolved = effectiveUdsPath(relative, targetPid = 1L)

        assertTrue(resolved.isAbsolute, "relative UDS path should be resolved, got $resolved")
        assertEquals(relative.toAbsolutePath(), resolved)
    }

    @Test
    fun `an absolute UDS path is left alone`() {
        val absolute = Path.of("/tmp", "sp-a-absolute", "agent.sock").toAbsolutePath()

        assertEquals(absolute, effectiveUdsPath(absolute, targetPid = 1L))
    }

    @Test
    fun `the default UDS path is already absolute`() {
        assertTrue(effectiveUdsPath(explicit = null, targetPid = 1L).isAbsolute)
    }

    @Test
    fun `attach rejects an explicit UDS path past the sun_path limit`() {
        val tooLong = Path.of("/tmp", "sp-" + "x".repeat(200), "agent.sock")

        val ex =
            assertFailsWith<UdsPathTooLongException> {
                AgentAttach.attach(pid = 1L, options = AttachOptions(udsPath = tooLong))
            }

        val message = ex.message.orEmpty()
        assertTrue(tooLong.toString() in message, "message should name the path, got: $message")
        assertTrue(
            "${UdsPathLimits.maxPathBytes}" in message,
            "message should name the sun_path limit, got: $message",
        )
    }
}

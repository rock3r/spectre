@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.UdsPathLimits
import java.nio.file.Path
import kotlin.test.Test
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

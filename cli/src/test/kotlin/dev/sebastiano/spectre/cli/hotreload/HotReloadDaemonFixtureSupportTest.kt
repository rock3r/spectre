package dev.sebastiano.spectre.cli.hotreload

import kotlin.test.Test
import kotlin.test.assertTrue

class HotReloadDaemonFixtureSupportTest {
    @Test
    fun `death after READY names the exit code and fixture output`() {
        val message =
            composeFixtureDiedAfterReadyMessage(
                exitValue = 1,
                outputTail =
                    "[fixture] semantics ready; emitting READY\nSPECTRE-FIXTURE-READY pid=9",
            )
        assertTrue(message.contains("exited after READY"), message)
        assertTrue(message.contains("exit=1"), message)
        assertTrue(message.contains("SPECTRE-FIXTURE-READY pid=9"), message)
    }

    @Test
    fun `death after READY reports a missing exit code instead of crashing`() {
        val message = composeFixtureDiedAfterReadyMessage(exitValue = null, outputTail = "")
        assertTrue(message.contains("exit=unknown"), message)
        assertTrue(message.contains("(empty)"), message)
    }
}

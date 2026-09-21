package dev.sebastiano.spectre.agent.fixture

import javax.swing.WindowConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The fixture is parent-owned: [ComposeFixtureMainKt] blocks on `Thread.join()` until the spawning
 * test destroys the process. [WindowConstants.EXIT_ON_CLOSE] contradicts that and lets a close-box
 * / WM close during a shared-display `./gradlew check` kill the JVM — the 0.7.0 Linux smoke `check`
 * cell (`HotReloadDaemonFixtureE2eTest` "exited after READY").
 */
class ComposeFixtureCloseOperationTest {
    @Test
    fun `fixture close must not System-exit the JVM`() {
        assertEquals(WindowConstants.DO_NOTHING_ON_CLOSE, FIXTURE_DEFAULT_CLOSE_OPERATION)
        assertNotEquals(WindowConstants.EXIT_ON_CLOSE, FIXTURE_DEFAULT_CLOSE_OPERATION)
    }
}

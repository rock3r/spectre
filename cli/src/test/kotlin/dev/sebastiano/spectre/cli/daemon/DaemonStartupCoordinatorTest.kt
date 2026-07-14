package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonStartupCoordinatorTest {
    @Test
    fun `starts once and retries when the daemon is absent`() {
        var attempts = 0
        var starts = 0

        DaemonStartupCoordinator(
                connect = {
                    attempts++
                    if (attempts == 1) throw IOException("missing")
                },
                start = { starts++ },
            )
            .connectOrStart()

        assertEquals(2, attempts)
        assertEquals(1, starts)
    }
}

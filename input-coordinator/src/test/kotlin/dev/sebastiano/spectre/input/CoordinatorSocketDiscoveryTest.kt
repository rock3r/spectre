@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #462 client half: a client must find the coordinator even when it had to fall forward past a
 * socket path the OS refuses to release.
 */
class CoordinatorSocketDiscoveryTest {

    private val first: Path = Path.of("/tmp/spc/input-v1-a.sock")
    private val second: Path = Path.of("/tmp/spc/input-v1-a-1.sock")
    private val third: Path = Path.of("/tmp/spc/input-v1-a-2.sock")

    @Test
    fun `the first reachable candidate wins`() {
        val attempted = mutableListOf<Path>()

        val found =
            CoordinatorSocketDiscovery.firstReachable(listOf(first, second, third)) { path ->
                attempted.add(path)
                path.toString()
            }

        assertEquals(first.toString(), found)
        assertEquals(listOf(first), attempted, "later candidates must not be probed after a hit")
    }

    @Test
    fun `unreachable candidates are skipped`() {
        val attempted = mutableListOf<Path>()

        val found =
            CoordinatorSocketDiscovery.firstReachable(listOf(first, second, third)) { path ->
                attempted.add(path)
                if (path == third) path.toString() else null
            }

        assertEquals(third.toString(), found)
        assertEquals(listOf(first, second, third), attempted)
    }

    @Test
    fun `a candidate that throws is treated as unreachable`() {
        // A dead socket file answers with an IO error rather than a clean refusal; it must not
        // abort the walk before a live coordinator further down the list is found.
        val found =
            CoordinatorSocketDiscovery.firstReachable(listOf(first, second)) { path ->
                if (path == first) error("dead socket") else path.toString()
            }

        assertEquals(second.toString(), found)
    }

    @Test
    fun `null is returned when nothing is reachable`() {
        val found = CoordinatorSocketDiscovery.firstReachable(listOf(first, second)) { null }

        assertNull(found)
    }
}

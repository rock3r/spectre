@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `a failure from a candidate that answered is not downgraded to unreachable`() {
        // A coordinator that answers with a malformed or incompatible frame is a real fault. If the
        // walk swallowed it, `LocalInputCoordinatorControl.status` would report NoActiveCoordinator
        // and the protocol problem would vanish.
        val failure =
            assertFailsWith<IllegalStateException> {
                CoordinatorSocketDiscovery.firstReachable(listOf(first, second)) { path ->
                    if (path == first) error("incompatible frame") else path.toString()
                }
            }

        assertEquals("incompatible frame", failure.message)
    }

    @Test
    fun `the walk stops at the failing candidate`() {
        val attempted = mutableListOf<Path>()

        assertFailsWith<IllegalStateException> {
            CoordinatorSocketDiscovery.firstReachable(listOf(first, second, third)) { path ->
                attempted.add(path)
                if (path == second) error("boom") else null
            }
        }

        assertEquals(listOf(first, second), attempted, "must not keep probing past a real failure")
    }

    @Test
    fun `null is returned when nothing is reachable`() {
        val found = CoordinatorSocketDiscovery.firstReachable(listOf(first, second)) { null }

        assertNull(found)
    }
}

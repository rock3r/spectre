@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #462 server half: choosing which socket path to bind. Kept free of real sockets so the decision
 * itself — including the single-owner guarantee — is testable on every platform.
 */
class CoordinatorSocketSelectorTest {

    private val first: Path = Path.of("/tmp/spc/input-v1-a.sock")
    private val second: Path = Path.of("/tmp/spc/input-v1-a-1.sock")
    private val third: Path = Path.of("/tmp/spc/input-v1-a-2.sock")
    private val candidates = listOf(first, second, third)

    @Test
    fun `the canonical path is used when it binds`() {
        val attempted = mutableListOf<Path>()

        val selection =
            CoordinatorSocketSelector.select(candidates) { path ->
                attempted.add(path)
                SocketBindAttempt.Bound(path, "listener@$path")
            }

        assertEquals(first, selection.path)
        assertEquals("listener@$first", selection.value)
        assertEquals(listOf(first), attempted, "must stop at the first successful bind")
    }

    @Test
    fun `an unusable path falls forward to the next generation`() {
        val attempted = mutableListOf<Path>()

        val selection =
            CoordinatorSocketSelector.select(candidates) { path ->
                attempted.add(path)
                if (path == first) {
                    SocketBindAttempt.Unusable("the file cannot be accessed by the system")
                } else {
                    SocketBindAttempt.Bound(path, "listener@$path")
                }
            }

        assertEquals(second, selection.path)
        assertEquals(listOf(first, second), attempted)
    }

    @Test
    fun `a live coordinator stops the walk so two servers never both own the desktop`() {
        val attempted = mutableListOf<Path>()

        val failure =
            assertFailsWith<IOException> {
                CoordinatorSocketSelector.select(candidates) { path ->
                    attempted.add(path)
                    if (path == first) SocketBindAttempt.LiveCoordinator
                    else SocketBindAttempt.Bound(path, "listener@$path")
                }
            }

        assertTrue(
            failure.message.orEmpty().contains("already listening"),
            "expected an already-listening failure, got: ${failure.message}",
        )
        assertEquals(listOf(first), attempted, "must not bind a later generation behind a live one")
    }

    @Test
    fun `a live coordinator found after an unusable path still stops the walk`() {
        // The poisoned-host shape: generation 0 is a corpse and the real owner sits on generation
        // 1. A second server must refuse, not leapfrog onto generation 2.
        val attempted = mutableListOf<Path>()

        val failure =
            assertFailsWith<IOException> {
                CoordinatorSocketSelector.select(candidates) { path ->
                    attempted.add(path)
                    when (path) {
                        first -> SocketBindAttempt.Unusable("corpse")
                        second -> SocketBindAttempt.LiveCoordinator
                        else -> SocketBindAttempt.Bound(path, "listener@$path")
                    }
                }
            }

        assertTrue(failure.message.orEmpty().contains("already listening"))
        assertEquals(listOf(first, second), attempted)
    }

    @Test
    fun `exhausting every candidate reports what was tried`() {
        val failure =
            assertFailsWith<IOException> {
                CoordinatorSocketSelector.select(candidates) {
                    SocketBindAttempt.Unusable("the file cannot be accessed by the system")
                }
            }

        val message = failure.message.orEmpty()
        assertTrue(message.contains(first.toString()), "should name the canonical path: $message")
        assertTrue(message.contains(third.toString()), "should name the last path tried: $message")
        assertTrue(
            message.contains("the file cannot be accessed by the system"),
            "should surface why the paths were unusable: $message",
        )
    }

    @Test
    fun `an empty candidate list is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CoordinatorSocketSelector.select(emptyList()) { SocketBindAttempt.Unusable("unused") }
        }
    }
}

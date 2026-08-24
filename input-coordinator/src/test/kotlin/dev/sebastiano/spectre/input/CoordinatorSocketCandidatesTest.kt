@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #462: a socket path that the OS refuses to release must not brick the host, so both the server
 * and its clients need the same deterministic list of fallback paths to walk.
 */
class CoordinatorSocketCandidatesTest {

    private val socketPath: Path = Path.of("/tmp/spectre-input-abcd1234/input-v1-abcd1234.sock")

    @Test
    fun `the canonical path is always tried first`() {
        val candidates = CoordinatorSocketCandidates.candidates(socketPath)

        assertEquals(socketPath, candidates.first())
    }

    @Test
    fun `fallback candidates are generation-suffixed siblings in the same directory`() {
        val candidates = CoordinatorSocketCandidates.candidates(socketPath)

        val fallbacks = candidates.drop(1)
        assertTrue(fallbacks.isNotEmpty(), "expected at least one fallback candidate")
        fallbacks.forEachIndexed { index, candidate ->
            assertEquals(socketPath.parent, candidate.parent)
            assertEquals("input-v1-abcd1234-${index + 1}.sock", candidate.fileName.toString())
        }
    }

    @Test
    fun `candidates are distinct and ordered by generation`() {
        val candidates = CoordinatorSocketCandidates.candidates(socketPath)

        assertEquals(candidates.distinct(), candidates)
        assertEquals(CoordinatorSocketCandidates.MAX_GENERATIONS, candidates.size)
    }

    @Test
    fun `the limit caps how many generations are produced`() {
        val candidates = CoordinatorSocketCandidates.candidates(socketPath, limit = 3)

        assertEquals(3, candidates.size)
    }

    @Test
    fun `candidates never exceed the safe unix socket path length`() {
        // A base path close to the limit must not produce fallbacks that the OS would reject for
        // length: a candidate we cannot bind is worse than one we never offer.
        val longDirectory = Path.of("/tmp/" + "d".repeat(70))
        val longSocket = longDirectory.resolve("input-v1-abcd1234.sock")

        val candidates = CoordinatorSocketCandidates.candidates(longSocket)

        assertTrue(candidates.isNotEmpty(), "the canonical path must always be offered")
        candidates.forEach { candidate ->
            val encoded = candidate.toString().toByteArray(StandardCharsets.UTF_8).size
            assertTrue(
                encoded <= CoordinatorSocketCandidates.MAX_SOCKET_PATH_BYTES,
                "candidate $candidate is $encoded bytes, over the safe maximum",
            )
        }
    }

    @Test
    fun `no fallback is offered when only the canonical path fits`() {
        // "/tmp/" + 72 + "/" + "input-v1-abcd1234.sock" is exactly the 100-byte maximum, so every
        // generation suffix would overflow it.
        val longSocket = Path.of("/tmp/" + "d".repeat(72)).resolve("input-v1-abcd1234.sock")

        val candidates = CoordinatorSocketCandidates.candidates(longSocket)

        assertEquals(listOf(longSocket), candidates)
    }

    @Test
    fun `a base path already over the limit still offers the canonical path`() {
        // Rejecting it here would turn a length problem into an empty candidate list; the endpoint
        // resolver already validates the canonical path and reports it properly.
        val longSocket = Path.of("/tmp/" + "d".repeat(76)).resolve("input-v1-abcd1234.sock")

        val candidates = CoordinatorSocketCandidates.candidates(longSocket)

        assertEquals(listOf(longSocket), candidates)
    }
}

package dev.sebastiano.spectre.sample

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic proof that the headed-contention verdict has teeth.
 *
 * [HeadedRobotContentionTest] can only observe whatever the desktop actually produced, so the
 * assertion it leans on is pinned here instead: an `ababab` field is the failure mode the headed
 * cell exists to rule out, and it must be reported as *interleaving* specifically — a verdict that
 * merely returns "not the expected string" would look like a valid red proof while actually failing
 * for the wrong reason.
 *
 * These cases run headless as part of `./gradlew check`; the headed e2e is tagged out of `test`.
 */
class HeadedRobotContentionAnalysisTest {

    @Test
    fun `two contiguous blocks pass in either order`() {
        assertNull(describeContentionFailure("aaaabbbb", 'a', 'b', blockLength = 4))
        assertNull(describeContentionFailure("bbbbaaaa", 'a', 'b', blockLength = 4))
    }

    @Test
    fun `perfectly alternating keystrokes are reported as interleaving`() {
        val failure = describeContentionFailure("abababab", 'a', 'b', blockLength = 4)
        assertNotNull(failure)
        assertTrue(
            failure.startsWith(INTERLEAVED_FAILURE),
            "interleaving must be named as such, got: $failure",
        )
        // The observed content belongs in the message: without it a reader cannot tell a clean
        // hand-off from a shredded one, and the release report only carries this string.
        assertTrue(failure.contains("abababab"), "failure must quote the field content: $failure")
    }

    @Test
    fun `a single spliced block is interleaving too`() {
        // The realistic shape when one JVM wins most of the race but not all of it.
        val failure = describeContentionFailure("aaabbbbaa", 'a', 'b', blockLength = 5)
        assertNotNull(failure)
        assertTrue(failure.startsWith(INTERLEAVED_FAILURE), "got: $failure")
    }

    @Test
    fun `dropped keystrokes are not reported as interleaving`() {
        // Two clean runs, but short. Real, and worth failing on — just not the same defect, and
        // calling it interleaving would send the next reader hunting a coordinator bug.
        val failure = describeContentionFailure("aaabbbb", 'a', 'b', blockLength = 4)
        assertNotNull(failure)
        assertTrue(
            !failure.startsWith(INTERLEAVED_FAILURE),
            "short blocks are a delivery failure, not interleaving: $failure",
        )
        assertTrue(failure.contains("3"), "failure must report the observed run lengths: $failure")
    }

    @Test
    fun `a field holding only one block fails`() {
        val failure = describeContentionFailure("aaaa", 'a', 'b', blockLength = 4)
        assertNotNull(failure)
        assertTrue(!failure.startsWith(INTERLEAVED_FAILURE), "got: $failure")
    }

    @Test
    fun `an empty field fails`() {
        assertNotNull(describeContentionFailure("", 'a', 'b', blockLength = 4))
    }

    @Test
    fun `foreign characters fail even when the two blocks are contiguous`() {
        // A stray modifier or a stuck key changes what reached the field; the run shape alone
        // would still look like two clean blocks.
        val failure = describeContentionFailure("aaaaXbbbb", 'a', 'b', blockLength = 4)
        assertNotNull(failure)
        assertTrue(failure.contains("X"), "failure must name the foreign character: $failure")
    }

    @Test
    fun `run length encoding collapses adjacent equal characters`() {
        assertTrue(typedRuns("aabbb") == listOf(TypedRun('a', 2), TypedRun('b', 3)))
        assertTrue(typedRuns("") == emptyList<TypedRun>())
    }
}

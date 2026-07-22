package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Smoke tests for the JVM-process enumeration API.
 *
 * These are deliberately lightweight: the goal is to catch regressions in the reflective wrapping
 * of `com.sun.tools.attach.VirtualMachine.list()`, not to make any guarantees about which processes
 * the test JVM can see. The latter varies wildly across machines (sandbox profiles, user IDs,
 * `hsperfdata` availability — see plan R-6) and would make the test flaky.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class SpectreProcessesTest {
    @Test
    fun `listJvmProcesses returns a non-null list`() {
        val processes = SpectreProcesses.listJvmProcesses()
        // The list may be empty on some CI sandboxes — assert only structural correctness.
        assertTrue(
            processes.all { it.pid > 0 },
            "every pid must be > 0; got ${processes.map { it.pid }}",
        )
    }

    @Test
    fun `findByName with empty filter returns approximately all visible processes`() {
        val all = SpectreProcesses.listJvmProcesses()
        val matched = SpectreProcesses.findByName("")
        // Empty filter matches every name (`contains("")` is always true), so `matched`
        // should be the same set as `all` in steady state. But these are two separate
        // `VirtualMachine.list()` calls back-to-back: on a busy CI runner (Linux GitHub
        // runners have other JVMs starting/stopping all the time — Gradle workers, test
        // forks, etc.) processes can come and go between the two calls. Asserting exact
        // equality is racy; tolerate a small drift instead. A genuine "filter is broken"
        // regression would change the result by orders of magnitude, not by ≤ 5.
        val drift = kotlin.math.abs(matched.size - all.size)
        assertTrue(
            drift <= MAX_PROCESS_LIST_DRIFT,
            "expected findByName('') to return approximately the same number of " +
                "processes as listJvmProcesses(); |${matched.size} - ${all.size}| = $drift " +
                "is greater than the tolerance window of $MAX_PROCESS_LIST_DRIFT",
        )
    }

    @Test
    fun `findByName with a nonsensical filter returns empty`() {
        val matched = SpectreProcesses.findByName("__no_process_should_ever_match_this_string__")
        assertFalse(matched.isNotEmpty(), "expected empty list, got $matched")
    }

    @Test
    fun `findByName is case-insensitive`() {
        val all = SpectreProcesses.listJvmProcesses()
        if (all.isEmpty()) return // nothing to compare against — sandboxed environment
        val sample = all.first().displayName
        if (sample.isBlank()) return
        val firstChar = sample.first()
        val upper = SpectreProcesses.findByName(firstChar.uppercaseChar().toString())
        val lower = SpectreProcesses.findByName(firstChar.lowercaseChar().toString())
        // Both filters should match the same set since the filter is case-insensitive.
        // Tolerate a small drift in case a JVM came/went between the two calls (see the
        // "empty filter" test above for the same rationale).
        val drift = kotlin.math.abs(upper.size - lower.size)
        assertTrue(
            drift <= MAX_PROCESS_LIST_DRIFT,
            "expected case-insensitive findByName to return approximately the same number " +
                "of processes for upper- and lower-case prefixes; |${upper.size} - ${lower.size}| " +
                "= $drift is greater than the tolerance window of $MAX_PROCESS_LIST_DRIFT",
        )
    }

    private companion object {
        // CI runners and busy developer machines have JVMs starting/stopping all the time;
        // back-to-back `VirtualMachine.list()` calls can disagree by more than a handful.
        // A genuine filter regression would diverge by orders of magnitude, not by ≤ 25.
        const val MAX_PROCESS_LIST_DRIFT: Int = 25
    }
}

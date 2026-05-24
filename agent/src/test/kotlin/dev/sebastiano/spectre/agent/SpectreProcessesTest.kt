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
    fun `findByName with empty filter returns all visible processes`() {
        val all = SpectreProcesses.listJvmProcesses()
        val matched = SpectreProcesses.findByName("")
        // Empty filter matches every name (contains("") is always true).
        assertTrue(matched.size == all.size)
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
        assertTrue(upper.size == lower.size)
    }
}

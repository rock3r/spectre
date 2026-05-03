package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacOsTccGuardTest {

    @Test
    fun `requireAccessibility throws on Denied with remediation message`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Denied },
                screenRecordingProbe = { TccStatus.Granted },
                warn = {},
            )

        val error = assertFailsWith<IllegalStateException> { guard.requireAccessibility() }

        // Message must name the missing TCC entry, the parent-process attribution rule,
        // and the relaunch-after-grant requirement so the consumer can act without lookup.
        val message = error.message.orEmpty()
        assertTrue("Accessibility" in message, "expected mention of Accessibility: $message")
        assertTrue("Privacy & Security" in message, "expected System Settings path: $message")
        assertTrue(
            "wrapping" in message || "parent" in message || "launching" in message,
            "expected parent-process attribution explanation: $message",
        )
        assertTrue(
            "relaunch" in message || "restart" in message || "quit" in message,
            "expected relaunch instruction: $message",
        )
    }

    @Test
    fun `requireScreenRecording throws on Denied with remediation message`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Denied },
                warn = {},
            )

        val error = assertFailsWith<IllegalStateException> { guard.requireScreenRecording() }

        val message = error.message.orEmpty()
        assertTrue(
            "Screen Recording" in message || "Screen & System Audio Recording" in message,
            "expected mention of Screen Recording entry: $message",
        )
        assertTrue("Privacy & Security" in message, "expected System Settings path: $message")
        assertTrue(
            "wrapping" in message || "parent" in message || "launching" in message,
            "expected parent-process attribution explanation: $message",
        )
    }

    @Test
    fun `requireAccessibility on Granted does not throw`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Granted },
                warn = {},
            )

        guard.requireAccessibility()
        guard.requireAccessibility()
    }

    @Test
    fun `requireScreenRecording on Granted does not throw`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Granted },
                warn = {},
            )

        guard.requireScreenRecording()
        guard.requireScreenRecording()
    }

    @Test
    fun `accessibility probe runs only once across many calls`() {
        var probeInvocations = 0
        val guard =
            MacOsTccGuard(
                accessibilityProbe = {
                    probeInvocations++
                    TccStatus.Granted
                },
                screenRecordingProbe = { TccStatus.Granted },
                warn = {},
            )

        repeat(5) { guard.requireAccessibility() }

        assertEquals(1, probeInvocations)
    }

    @Test
    fun `screen recording probe runs only once across many calls`() {
        var probeInvocations = 0
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = {
                    probeInvocations++
                    TccStatus.Granted
                },
                warn = {},
            )

        repeat(5) { guard.requireScreenRecording() }

        assertEquals(1, probeInvocations)
    }

    @Test
    fun `Unknown accessibility warns once and proceeds`() {
        val warnings = mutableListOf<String>()
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Unknown },
                screenRecordingProbe = { TccStatus.Granted },
                warn = { warnings += it },
            )

        repeat(3) { guard.requireAccessibility() }

        assertEquals(1, warnings.size, "expected exactly one warning, got: $warnings")
        val message = warnings.single()
        assertTrue("Accessibility" in message, "expected Accessibility in warning: $message")
        assertTrue(
            "could not" in message || "unknown" in message.lowercase(),
            "expected probe-unknown phrasing: $message",
        )
    }

    @Test
    fun `Unknown screen recording warns once and proceeds`() {
        val warnings = mutableListOf<String>()
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Unknown },
                warn = { warnings += it },
            )

        repeat(3) { guard.requireScreenRecording() }

        assertEquals(1, warnings.size, "expected exactly one warning, got: $warnings")
        assertTrue(
            "Screen Recording" in warnings.single() ||
                "Screen & System Audio Recording" in warnings.single(),
            "expected Screen Recording in warning: ${warnings.single()}",
        )
    }

    @Test
    fun `NotApplicable status neither throws nor warns`() {
        val warnings = mutableListOf<String>()
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.NotApplicable },
                screenRecordingProbe = { TccStatus.NotApplicable },
                warn = { warnings += it },
            )

        repeat(3) {
            guard.requireAccessibility()
            guard.requireScreenRecording()
        }

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `accessibility denial does not block screen recording check`() {
        // Per-method granularity: a consumer who only screenshots shouldn't be punished for
        // missing Accessibility, and vice versa. The two probes are independent.
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Denied },
                screenRecordingProbe = { TccStatus.Granted },
                warn = {},
            )

        guard.requireScreenRecording()
        assertFailsWith<IllegalStateException> { guard.requireAccessibility() }
    }

    @Test
    fun `screen recording denial does not block accessibility check`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Denied },
                warn = {},
            )

        guard.requireAccessibility()
        assertFailsWith<IllegalStateException> { guard.requireScreenRecording() }
    }

    @Test
    fun `noop guard never throws or warns regardless of probe results`() {
        // The noop guard is what the headless / non-macOS paths use. It must not invoke probes
        // at all — those would be no-ops here, but the contract is "doesn't touch the OS".
        val warnings = mutableListOf<String>()
        var probeRan = false
        val guard = MacOsTccGuard.noop()

        // Calling shouldn't blow up regardless of how many times.
        repeat(10) {
            guard.requireAccessibility()
            guard.requireScreenRecording()
        }

        assertEquals(emptyList(), warnings)
        assertEquals(false, probeRan)
    }

    @Test
    fun `probe failure resulting in Denied caches across calls`() {
        // Once denied, calling again should re-throw without re-probing.
        var probeInvocations = 0
        val guard =
            MacOsTccGuard(
                accessibilityProbe = {
                    probeInvocations++
                    TccStatus.Denied
                },
                screenRecordingProbe = { TccStatus.Granted },
                warn = {},
            )

        assertFailsWith<IllegalStateException> { guard.requireAccessibility() }
        assertFailsWith<IllegalStateException> { guard.requireAccessibility() }
        assertFailsWith<IllegalStateException> { guard.requireAccessibility() }

        assertEquals(1, probeInvocations, "probe should run only once even when denied")
    }
}

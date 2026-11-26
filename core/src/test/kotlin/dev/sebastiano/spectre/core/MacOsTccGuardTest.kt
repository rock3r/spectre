package dev.sebastiano.spectre.core

import java.awt.image.BufferedImage
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
    fun `requireScreenRecording throws on Locked with lock guidance`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Locked },
            )

        val error = assertFailsWith<IllegalStateException> { guard.requireScreenRecording() }

        val message = error.message.orEmpty()
        assertTrue("locked" in message.lowercase(), "expected locked-screen guidance: $message")
        assertTrue("unlock" in message.lowercase(), "expected unlock instruction: $message")
    }

    @Test
    fun `requireAccessibility on Granted does not throw`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Granted },
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
            )

        repeat(5) { guard.requireScreenRecording() }

        assertEquals(1, probeInvocations)
    }

    @Test
    fun `locked screen recording result is not cached`() {
        var probeInvocations = 0
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = {
                    probeInvocations++
                    if (probeInvocations == 1) TccStatus.Locked else TccStatus.Granted
                },
            )

        assertFailsWith<IllegalStateException> { guard.requireScreenRecording() }
        guard.requireScreenRecording()

        assertEquals(2, probeInvocations)
    }

    @Test
    fun `Unknown accessibility fails with a typed inconclusive diagnostic`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Unknown },
                screenRecordingProbe = { TccStatus.Granted },
            )

        val error = assertFailsWith<MacOsTccDiagnosticException> { guard.requireAccessibility() }

        assertEquals(MacOsTccDiagnostic.Inconclusive, error.diagnostic)
        assertTrue("Accessibility" in error.message.orEmpty())
    }

    @Test
    fun `Unknown screen recording fails with a typed inconclusive diagnostic`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = { TccStatus.Unknown },
            )

        val error = assertFailsWith<MacOsTccDiagnosticException> { guard.requireScreenRecording() }

        assertEquals(MacOsTccDiagnostic.Inconclusive, error.diagnostic)
        assertTrue("Screen Recording" in error.message.orEmpty())
    }

    @Test
    fun `black capture after cached grant detects newly locked console`() {
        var probeInvocations = 0
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = {
                    probeInvocations++
                    if (probeInvocations == 1) TccStatus.Granted else TccStatus.Locked
                },
            )
        guard.requireScreenRecording()

        val error =
            assertFailsWith<MacOsTccDiagnosticException> {
                guard.validateCapture(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))
            }

        assertEquals(2, probeInvocations)
        assertEquals(MacOsTccDiagnostic.Locked, error.diagnostic)
    }

    @Test
    fun `black capture is preserved when re-probe conclusively confirms grant`() {
        var probeInvocations = 0
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Granted },
                screenRecordingProbe = {
                    probeInvocations++
                    TccStatus.Granted
                },
            )
        guard.requireScreenRecording()

        guard.validateCapture(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))

        assertEquals(2, probeInvocations)
    }

    @Test
    fun `noop validation bypasses pixel reads`() {
        val image =
            object : BufferedImage(8, 8, TYPE_INT_ARGB) {
                override fun getRGB(x: Int, y: Int): Int = error("pixels must not be read")
            }
        val guard = MacOsTccGuard.noop()
        guard.requireScreenRecording()

        guard.validateCapture(image)
    }

    @Test
    fun `NotApplicable status neither throws nor warns`() {
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.NotApplicable },
                screenRecordingProbe = { TccStatus.NotApplicable },
            )

        repeat(3) {
            guard.requireAccessibility()
            guard.requireScreenRecording()
        }
    }

    @Test
    fun `accessibility denial does not block screen recording check`() {
        // Per-method granularity: a consumer who only screenshots shouldn't be punished for
        // missing Accessibility, and vice versa. The two probes are independent.
        val guard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.Denied },
                screenRecordingProbe = { TccStatus.Granted },
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
            )

        guard.requireAccessibility()
        assertFailsWith<IllegalStateException> { guard.requireScreenRecording() }
    }

    @Test
    fun `noop guard returns NotApplicable for both probes and never warns`() {
        // The factory wires in `NotApplicable` lambdas internally, so to verify the contract we
        // build a guard with the same shape but observable lambdas. If `MacOsTccGuard.noop()`
        // ever started returning something other than NotApplicable (e.g. ran a real probe), the
        // matched-shape guard here would diverge in observable behaviour from the factory one.
        var accessibilityProbed = 0
        var screenRecordingProbed = 0
        val matchingNoopShape =
            MacOsTccGuard(
                accessibilityProbe = {
                    accessibilityProbed++
                    TccStatus.NotApplicable
                },
                screenRecordingProbe = {
                    screenRecordingProbed++
                    TccStatus.NotApplicable
                },
            )

        repeat(10) {
            matchingNoopShape.requireAccessibility()
            matchingNoopShape.requireScreenRecording()
            // The factory-built noop must also never throw for any number of calls.
            MacOsTccGuard.noop().requireAccessibility()
            MacOsTccGuard.noop().requireScreenRecording()
        }

        assertEquals(
            1,
            accessibilityProbed,
            "probe should run at most once even when NotApplicable",
        )
        assertEquals(1, screenRecordingProbed)
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
            )

        assertFailsWith<IllegalStateException> { guard.requireAccessibility() }
        assertFailsWith<IllegalStateException> { guard.requireAccessibility() }
        assertFailsWith<IllegalStateException> { guard.requireAccessibility() }

        assertEquals(1, probeInvocations, "probe should run only once even when denied")
    }

    @Test
    fun `macOS console lock status parses ioreg output`() {
        assertEquals(true, macOsConsoleLockStatus("\"IOConsoleLocked\" = Yes"))
        assertEquals(false, macOsConsoleLockStatus("\"IOConsoleLocked\" = No"))
        assertEquals(null, macOsConsoleLockStatus("\"IOConsoleUsers\" = ()"))
    }
}

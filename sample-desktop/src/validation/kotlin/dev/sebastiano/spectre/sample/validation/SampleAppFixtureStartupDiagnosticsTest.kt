package dev.sebastiano.spectre.sample.validation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pure diagnostics for [SampleAppFixture] startup failures — no AWT display required.
 *
 * Keeps the Windows cold-start failure message contract locked so CI flakes stay diagnosable when
 * `application {}` is slow or dies before the latch trips.
 */
class SampleAppFixtureStartupDiagnosticsTest {

    @Test
    fun `default startup timeout leaves headroom for cold Windows CI JVMs`() {
        // Regression lock for the AtomicCaptureValidationTest flake that timed out at 10s on
        // validation-windows (GHA) before application{} entered.
        assertTrue(
            SampleAppFixture.DEFAULT_STARTUP_TIMEOUT >= 30.seconds,
            "DEFAULT_STARTUP_TIMEOUT must be at least 30s; was ${SampleAppFixture.DEFAULT_STARTUP_TIMEOUT}",
        )
    }

    @Test
    fun `timeout failure mentions budget and thread liveness`() {
        val message =
            describeApplicationStartupFailure(
                timeout = 30.seconds,
                enteredApplication = false,
                threadAlive = true,
                startupError = null,
            )
        assertTrue(message.contains("did not enter application{}"))
        assertTrue(message.contains("30s") || message.contains("30.0s"))
        assertTrue(message.contains("fixture thread still alive"))
        assertFalse(message.contains("cause="))
    }

    @Test
    fun `startup exception is folded into the failure message`() {
        val message =
            describeApplicationStartupFailure(
                timeout = 30.seconds,
                enteredApplication = true,
                threadAlive = false,
                startupError = IllegalStateException("compose exploded"),
            )
        assertTrue(message.contains("failed during startup"))
        assertTrue(message.contains("fixture thread already exited"))
        assertTrue(message.contains("IllegalStateException"))
        assertTrue(message.contains("compose exploded"))
    }
}

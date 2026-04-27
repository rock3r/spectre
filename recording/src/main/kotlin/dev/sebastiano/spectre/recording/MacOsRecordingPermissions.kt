package dev.sebastiano.spectre.recording

import java.util.concurrent.TimeUnit

/**
 * Best-effort startup diagnostics for the macOS permissions Spectre needs.
 *
 * The Spectre automator interacts with the OS in two ways that gated by macOS TCC permissions:
 * - **Screen Recording** — required for [FfmpegRecorder]'s avfoundation device and for AWT
 *   `Robot.createScreenCapture` (so [dev.sebastiano.spectre.core.RobotDriver]'s screenshot path on
 *   macOS).
 * - **Accessibility (input control)** — required for AWT `Robot` to actually move the mouse and
 *   synthesize key events. Without it, mouse/key calls silently no-op.
 *
 * Detecting these without JNI is awkward — the canonical native APIs
 * (`CGPreflightScreenCaptureAccess`, `AXIsProcessTrusted`) live in CoreGraphics /
 * ApplicationServices and aren't reachable from pure JVM. v1 ships the diagnostic surface as
 * documentation plus a probe-style heuristic via the `tccutil`/`osascript` CLIs where available;
 * full native detection is deferred to v2 once the ScreenCaptureKit work brings in JNI/Panama
 * bindings anyway.
 */
object MacOsRecordingPermissions {

    /**
     * Returns a human-readable diagnostic that downstream tooling can dump at startup. On non-macOS
     * platforms returns [PermissionStatus.NotApplicable]. On macOS, runs a best-effort probe via
     * `osascript` if available, otherwise returns [PermissionStatus.Unknown] with guidance text.
     */
    fun diagnose(): PermissionDiagnostic {
        if (!isMacOs()) return PermissionDiagnostic(NOT_MAC_MESSAGE, PermissionStatus.NotApplicable)
        val screen = probeScreenRecordingPermission()
        val accessibility = probeAccessibilityPermission()
        return PermissionDiagnostic(
            message =
                buildString {
                    appendLine("Spectre macOS permission diagnostics:")
                    appendLine("  Screen Recording   : ${screen.label}")
                    appendLine("  Accessibility       : ${accessibility.label}")
                    appendLine()
                    appendLine(GUIDANCE)
                },
            status = combine(screen, accessibility),
        )
    }

    private fun probeScreenRecordingPermission(): PermissionStatus {
        // CGPreflightScreenCaptureAccess lives in CoreGraphics and isn't reachable from pure
        // JVM. There's no AppleScript proxy for the Screen Recording TCC entry either, so for
        // v1 we honestly report Unknown rather than pretending we know — full native detection
        // is deferred to v2 alongside the ScreenCaptureKit JNI/Panama work.
        return PermissionStatus.Unknown
    }

    private fun probeAccessibilityPermission(): PermissionStatus {
        // The probe asks System Events for a process name. Three outcomes:
        //   - exit 0 with non-blank output: Accessibility is granted.
        //   - exit non-zero with the explicit Accessibility denial string: Denied.
        //   - exit non-zero with anything else (including the AppleEvents/Automation
        //     authorisation error -1743 / "Not authorized to send Apple events to System
        //     Events"): Unknown — Automation and Accessibility are separate macOS TCC
        //     entries, so an Automation refusal does NOT prove Accessibility is denied.
        val result =
            runOsascript("tell application \"System Events\" to return name of first process")
        return when {
            result == null -> PermissionStatus.Unknown
            result.exitCode != 0 -> {
                if (result.output.contains("not allowed assistive access", ignoreCase = true)) {
                    PermissionStatus.Denied
                } else {
                    PermissionStatus.Unknown
                }
            }
            result.output.isNotBlank() -> PermissionStatus.Granted
            else -> PermissionStatus.Unknown
        }
    }

    /** Combine multiple statuses into a single rollup using the worst-known value. */
    private fun combine(vararg statuses: PermissionStatus): PermissionStatus =
        when {
            statuses.any { it == PermissionStatus.Denied } -> PermissionStatus.Denied
            statuses.any { it == PermissionStatus.Unknown } -> PermissionStatus.Unknown
            statuses.all { it == PermissionStatus.Granted } -> PermissionStatus.Granted
            else -> PermissionStatus.NotApplicable
        }

    @Suppress("TooGenericExceptionCaught")
    private fun runOsascript(script: String): OsascriptResult? {
        val process =
            try {
                ProcessBuilder("osascript", "-e", script).redirectErrorStream(true).start()
            } catch (_: Throwable) {
                return null
            }
        return try {
            if (!process.waitFor(OSASCRIPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else {
                OsascriptResult(
                    output = process.inputStream.bufferedReader().use { it.readText() }.trim(),
                    exitCode = process.exitValue(),
                )
            }
        } catch (_: InterruptedException) {
            // Cancellation is meaningful upstream — surface the interrupt so callers'
            // timeout/cancellation logic can react instead of silently swallowing it.
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            null
        } catch (_: Throwable) {
            process.destroyForcibly()
            null
        }
    }

    private data class OsascriptResult(val output: String, val exitCode: Int)

    private fun isMacOs(): Boolean = System.getProperty("os.name").lowercase().contains("mac")

    private const val OSASCRIPT_TIMEOUT_SECONDS: Long = 3

    private const val NOT_MAC_MESSAGE: String =
        "Spectre macOS permission diagnostics: not applicable on this platform."

    private const val GUIDANCE: String =
        "If a status is Denied or Unknown, grant the JVM (your IDE / your test runner / java)\n" +
            "the relevant permission under System Settings → Privacy & Security:\n" +
            "  - Screen Recording: required for ffmpeg avfoundation capture and for Robot screenshots.\n" +
            "  - Accessibility:    required for Robot mouse/keyboard control.\n" +
            "Restart the JVM after granting; macOS does not refresh TCC entitlements live."
}

/** Coarse permission status we can detect without JNI. */
enum class PermissionStatus(val label: String) {
    Granted("granted"),
    Denied("denied"),
    Unknown("unknown (could not detect — see guidance)"),
    NotApplicable("not applicable on this platform"),
}

/** Result of [MacOsRecordingPermissions.diagnose]. */
data class PermissionDiagnostic(val message: String, val status: PermissionStatus)

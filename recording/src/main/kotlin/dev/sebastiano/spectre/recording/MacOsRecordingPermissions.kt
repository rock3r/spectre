package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.MacOsScreenCaptureAccess
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureAccessResult
import java.util.concurrent.TimeUnit

/**
 * Startup diagnostics for the macOS permissions Spectre needs.
 *
 * - **Screen Recording** — [MacOsScreenCaptureAccess.preflight] via the ScreenCaptureKit helper
 *   (`CGPreflightScreenCaptureAccess`). Never prompts.
 * - **Accessibility** — best-effort probe via `osascript` / System Events (Automation is a separate
 *   TCC entry and may leave Accessibility unknown).
 */
public object MacOsRecordingPermissions {

    /**
     * Returns a human-readable diagnostic that downstream tooling can dump at startup. On non-macOS
     * platforms returns [PermissionStatus.NotApplicable].
     */
    public fun diagnose(): PermissionDiagnostic {
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

    /**
     * Screen Recording only — structured result for agents/CLI. Never prompts. Prefer this over
     * [diagnose] when only capture access matters.
     */
    public fun preflightScreenRecording(): ScreenCaptureAccessResult =
        MacOsScreenCaptureAccess.preflight()

    private fun probeScreenRecordingPermission(): PermissionStatus {
        return try {
            val result = MacOsScreenCaptureAccess.preflight()
            if (result.granted) PermissionStatus.Granted else PermissionStatus.Denied
        } catch (_: Exception) {
            PermissionStatus.Unknown
        }
    }

    private fun probeAccessibilityPermission(): PermissionStatus {
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
        "If Screen Recording is Denied, run `spectre permissions request` with a human present,\n" +
            "or open ${MacOsScreenCaptureAccess.SETTINGS_PATH}\n" +
            "(${MacOsScreenCaptureAccess.DEEP_LINK}) and enable the spectre-screencapture helper\n" +
            "and/or the host JVM. Restart the process after granting.\n" +
            "Accessibility is required for Robot mouse/keyboard control."
}

/** Coarse permission status we can detect without JNI. */
public enum class PermissionStatus(public val label: String) {
    Granted("granted"),
    Denied("denied"),
    Unknown("unknown (could not detect — see guidance)"),
    NotApplicable("not applicable on this platform"),
}

/** Result of [MacOsRecordingPermissions.diagnose]. */
public data class PermissionDiagnostic(val message: String, val status: PermissionStatus)

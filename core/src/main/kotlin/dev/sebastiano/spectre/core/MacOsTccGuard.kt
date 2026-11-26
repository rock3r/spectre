package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit

/** Machine-readable reason why Spectre refused a macOS TCC-gated operation. */
public enum class MacOsTccDiagnostic {
    Denied,
    Locked,
    Inconclusive,
}

/** Loud failure when a macOS permission probe cannot establish that an operation is safe. */
public class MacOsTccDiagnosticException(
    public val diagnostic: MacOsTccDiagnostic,
    message: String,
) : IllegalStateException(message)

/**
 * Coarse macOS TCC permission status, mirrored from
 * `dev.sebastiano.spectre.recording.PermissionStatus` so `:core` can probe without depending on
 * `:recording`.
 */
internal enum class TccStatus {
    Granted,
    Denied,
    Locked,
    Unknown,
    NotApplicable,
}

/**
 * Lazy, cached TCC permission gate for the AWT [java.awt.Robot]-backed `RobotDriver` on macOS.
 *
 * `java.awt.Robot`'s mouse / keyboard / screenshot methods are gated on TCC entries that the JVM
 * cannot query through any public API: **Accessibility** (input delivery) and **Screen Recording**
 * (capture). Without the right grant, those calls silently no-op or return all-black pixels — the
 * failure surfaces downstream as "click did nothing" or "image is black", with the cause invisible.
 *
 * This guard runs a probe on first use (per category, independently — clicks don't fail because
 * Screen Recording is missing) and:
 *
 * - **Denied** — throws [IllegalStateException] with an actionable remediation message that names
 *   the missing TCC entry, the parent-process attribution rule, and the relaunch-after-grant
 *   requirement. The exception is the loud signal the silent-no-op shape was missing.
 * - **Unknown** — throws a typed inconclusive diagnostic rather than proceeding unsafely.
 * - **Granted** / **NotApplicable** — silent pass-through.
 *
 * Probe results are cached after the first call: the osascript Accessibility query takes a few
 * hundred milliseconds, and the Screen Recording probe burns a real `Robot.createScreenCapture`
 * round-trip, so repeated calls go through a memoised status check.
 *
 * The headless / non-macOS paths use [noop], which never invokes a probe at all — there's no
 * `Robot` to gate.
 */
internal open class MacOsTccGuard(
    private val accessibilityProbe: () -> TccStatus,
    private val screenRecordingProbe: () -> TccStatus,
) {

    @Volatile private var accessibilityStatus: TccStatus? = null
    @Volatile private var screenRecordingStatus: TccStatus? = null

    open fun requireAccessibility() {
        applyStatus(
            cached = accessibilityStatus,
            probe = accessibilityProbe,
            store = { accessibilityStatus = it },
            deniedMessage = ACCESSIBILITY_DENIED_MESSAGE,
            unknownWarning = ACCESSIBILITY_UNKNOWN_WARNING,
        )
    }

    open fun requireScreenRecording() {
        applyStatus(
            cached = screenRecordingStatus,
            probe = screenRecordingProbe,
            store = { screenRecordingStatus = it },
            deniedMessage = SCREEN_RECORDING_DENIED_MESSAGE,
            lockedMessage = SCREEN_RECORDING_LOCKED_MESSAGE,
            unknownWarning = SCREEN_RECORDING_UNKNOWN_WARNING,
        )
    }

    /** Re-probes this Robot owner's cached grant when its production capture returns black. */
    open fun validateCapture(image: BufferedImage) {
        // The noop guard represents a backend with no macOS Robot TCC identity. Avoid scanning the
        // image there (and on every non-macOS driver) before doing any pixel work.
        if (screenRecordingStatus == TccStatus.NotApplicable || !image.isAllBlack()) return
        screenRecordingStatus = null
        requireScreenRecording()
    }

    private inline fun applyStatus(
        cached: TccStatus?,
        probe: () -> TccStatus,
        store: (TccStatus) -> Unit,
        deniedMessage: String,
        lockedMessage: String? = null,
        unknownWarning: String,
    ) {
        val status =
            cached
                ?: probe().also {
                    // A locked screen is transient; do not memoise it as a permanent capture
                    // denial for long-lived Gradle daemons or IDE-hosted test JVMs.
                    if (it != TccStatus.Locked && it != TccStatus.Unknown) store(it)
                }
        when (status) {
            TccStatus.Denied ->
                throw MacOsTccDiagnosticException(MacOsTccDiagnostic.Denied, deniedMessage)
            TccStatus.Locked ->
                throw MacOsTccDiagnosticException(
                    MacOsTccDiagnostic.Locked,
                    lockedMessage ?: deniedMessage,
                )
            TccStatus.Unknown ->
                throw MacOsTccDiagnosticException(
                    MacOsTccDiagnostic.Inconclusive,
                    unknownWarning,
                )
            TccStatus.Granted,
            TccStatus.NotApplicable -> Unit
        }
    }

    companion object {

        /** A guard that performs no probes — used for headless adapters and non-macOS platforms. */
        fun noop(): MacOsTccGuard =
            MacOsTccGuard(
                accessibilityProbe = { TccStatus.NotApplicable },
                screenRecordingProbe = { TccStatus.NotApplicable },
            )
    }
}

/**
 * Probes Accessibility TCC by asking System Events (via `osascript`) for a process name. Three
 * outcomes:
 *
 * - exit 0 with non-blank output → Granted.
 * - exit non-zero with the explicit `not allowed assistive access` denial string → Denied.
 * - anything else (including the AppleEvents/Automation -1743 refusal — Automation and
 *   Accessibility are separate TCC entries, so an Automation refusal does NOT prove Accessibility
 *   is denied) → Unknown.
 */
internal fun osascriptAccessibilityProbe(): TccStatus {
    val result =
        runOsascript("tell application \"System Events\" to return name of first process")
            ?: return TccStatus.Unknown
    return when {
        result.exitCode == 0 && result.output.isNotBlank() -> TccStatus.Granted
        result.exitCode != 0 &&
            result.output.contains("not allowed assistive access", ignoreCase = true) ->
            TccStatus.Denied
        else -> TccStatus.Unknown
    }
}

/**
 * Probes Screen Recording TCC by capturing a small region near the screen origin (which on macOS
 * overlaps the menu bar, virtually never fully black) via the supplied [screenCapture] adapter. If
 * every pixel has zero RGB the result is inconclusive: denial, a locked session, and legitimate
 * black content are indistinguishable from pixels alone.
 */
internal fun robotScreenRecordingProbe(screenCapture: ScreenCaptureAdapter): TccStatus {
    val image = runCatching {
        screenCapture.createScreenCapture(Rectangle(0, 0, PROBE_SIZE_PX, PROBE_SIZE_PX))
    }
        .getOrElse {
            return TccStatus.Unknown
        }
    if (image.width <= 1 || image.height <= 1) {
        // Synthetic adapters (or a heavily-restricted environment) can return a 1×1 placeholder;
        // the probe can't say anything useful in that case.
        return TccStatus.Unknown
    }
    return if (image.isAllBlack()) TccStatus.Unknown else TccStatus.Granted
}

private fun BufferedImage.isAllBlack(): Boolean {
    for (y in 0 until height) {
        for (x in 0 until width) {
            if ((getRGB(x, y) and RGB_MASK) != 0) return false
        }
    }
    return true
}

internal fun macOsScreenRecordingProbe(screenCapture: ScreenCaptureAdapter): TccStatus {
    if (macOsConsoleLockedProbe() == true) return TccStatus.Locked
    return robotScreenRecordingProbe(screenCapture)
}

internal fun macOsConsoleLockStatus(ioregOutput: String): Boolean? {
    val match = Regex(""""IOConsoleLocked"\s*=\s*(Yes|No)""").find(ioregOutput) ?: return null
    return match.groupValues[1] == "Yes"
}

@Suppress("TooGenericExceptionCaught")
private fun macOsConsoleLockedProbe(): Boolean? {
    val process =
        try {
            ProcessBuilder("/usr/sbin/ioreg", "-n", "Root", "-d", "1", "-r")
                .redirectErrorStream(true)
                .start()
        } catch (_: Throwable) {
            return null
        }
    return try {
        if (!process.waitFor(IOREG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            macOsConsoleLockStatus(process.inputStream.bufferedReader().use { it.readText() })
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

@Suppress("TooGenericExceptionCaught")
private fun runOsascript(script: String): OsascriptResult? {
    // Mirrors `dev.sebastiano.spectre.recording.MacOsRecordingPermissions.runOsascript` — kept in
    // sync deliberately, since `:core` and `:recording` are isolated modules (see ARCHITECTURE.md)
    // and neither can import the other. Unexpected exceptions from `waitFor` / stream reads / the
    // process API surface should yield `null` (Unknown probe outcome), not propagate up and crash
    // the caller: a probe that crashes is strictly worse than a probe that says "I don't know".
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

private const val OSASCRIPT_TIMEOUT_SECONDS: Long = 3
private const val IOREG_TIMEOUT_SECONDS: Long = 3
private const val PROBE_SIZE_PX: Int = 32
// Mask out the alpha channel from BufferedImage.TYPE_INT_ARGB pixels so probe checks see the
// 24-bit RGB tuple. A blocked capture can return RGB=0 across every pixel; alpha may not be 0.
private const val RGB_MASK: Int = 0x00FFFFFF

private const val PARENT_PROCESS_GUIDANCE: String =
    "macOS attributes Robot operations to the wrapping app that launched the JVM (Terminal, " +
        "iTerm2, IntelliJ IDEA, Claude.app, etc.) — not to the JVM binary itself. Grant the " +
        "permission to the launching app, then fully quit and relaunch it (macOS only " +
        "re-evaluates TCC at process start; restart the Gradle daemon too via " +
        "`./gradlew --stop` if you launched from a shell)."

internal const val ACCESSIBILITY_DENIED_MESSAGE: String =
    "Spectre RobotDriver: macOS Accessibility TCC permission is denied — Robot mouse and keyboard " +
        "input would be silently dropped by the OS. Grant System Settings → Privacy & Security " +
        "→ Accessibility to the wrapping app that launched this JVM. $PARENT_PROCESS_GUIDANCE " +
        "Use RobotDriver.headless() if you do not need real OS input."

internal const val ACCESSIBILITY_UNKNOWN_WARNING: String =
    "Spectre RobotDriver: could not determine macOS Accessibility TCC permission state " +
        "(osascript probe was inconclusive). Robot mouse / keyboard input may be silently " +
        "dropped if the wrapping app has not been granted System Settings → Privacy & Security " +
        "→ Accessibility. $PARENT_PROCESS_GUIDANCE"

internal const val SCREEN_RECORDING_DENIED_MESSAGE: String =
    "Spectre RobotDriver: macOS Screen Recording TCC permission is denied — Robot screen " +
        "capture returns an all-black image instead of throwing. Grant System Settings → " +
        "Privacy & Security → Screen & System Audio Recording to the wrapping app that " +
        "launched this JVM. $PARENT_PROCESS_GUIDANCE On macOS 26+ the toggle grants " +
        "picker-based access only; the first direct screen-capture call also prompts a " +
        "second 'bypass the system private window picker' dialog that you must accept."

internal const val SCREEN_RECORDING_LOCKED_MESSAGE: String =
    "Spectre RobotDriver: macOS screen capture is unavailable because the console session is " +
        "locked — Robot screen capture may return an all-black image while locked. Unlock the " +
        "screen and retry. If capture still fails after unlocking, grant System Settings → " +
        "Privacy & Security → Screen & System Audio Recording to the wrapping app that " +
        "launched this JVM. $PARENT_PROCESS_GUIDANCE"

internal const val SCREEN_RECORDING_UNKNOWN_WARNING: String =
    "Spectre RobotDriver: could not determine macOS Screen Recording TCC permission state " +
        "(probe capture was inconclusive, so capture was refused without prompting). An all-black " +
        "frame can mean a locked session, a missing grant, or legitimate black content. Unlock " +
        "the session and verify System Settings → Privacy & Security → " +
        "Screen & System Audio Recording. $PARENT_PROCESS_GUIDANCE"

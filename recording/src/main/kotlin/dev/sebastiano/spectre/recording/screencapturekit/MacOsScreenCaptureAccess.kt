package dev.sebastiano.spectre.recording.screencapturekit

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * macOS Screen Recording TCC preflight / request via the `spectre-screencapture` helper.
 *
 * Agents cannot click TCC prompts (SecurityAgent is not automatable). Capture paths must call
 * [preflight] and fail fast when access is missing. Only [request] may call
 * `CGRequestScreenCaptureAccess` — and only behind an explicit human-invoked CLI/MCP command.
 *
 * On non-macOS hosts, [preflight] returns [ScreenCaptureAccessResult.notApplicable].
 */
public object MacOsScreenCaptureAccess {

    public const val SETTINGS_PATH: String =
        "System Settings → Privacy & Security → Screen & System Audio Recording"
    public const val DEEP_LINK: String =
        "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"
    public const val HELPER_EXIT_NOT_GRANTED: Int = 6

    private val json = Json { ignoreUnknownKeys = true }

    /** Check Screen Recording without prompting. */
    public fun preflight(): ScreenCaptureAccessResult =
        preflight(HelperBinaryExtractor(), DefaultProcessFactory)

    /**
     * Human-only guided permission flow (#192). Launches the helper's SwiftUI guide window when
     * Screen Recording is missing; returns immediately if already granted. **Never call from
     * automated capture/recording paths** — those must use [requireGranted] / [preflight] only.
     */
    public fun request(): ScreenCaptureAccessResult =
        request(HelperBinaryExtractor(), DefaultProcessFactory)

    /**
     * Fail fast when Screen Recording is missing. No-op on non-macOS. Throws
     * [ScreenCaptureAccessDeniedException] with a structured, agent-relayable message.
     */
    public fun requireGranted() {
        if (!isMacOs()) return
        val result = preflight()
        if (!result.granted) throw ScreenCaptureAccessDeniedException(result)
    }

    /**
     * Test-visible overload that injects helper extraction, process spawning, and the OS gate. Pass
     * [isMacOs] = `{ true }` so fake-helper tests exercise the process path on Linux CI.
     */
    internal fun preflight(
        helperExtractor: HelperBinaryExtractor,
        processFactory: ProcessFactory,
        isMacOs: () -> Boolean = ::isMacOs,
    ): ScreenCaptureAccessResult {
        if (!isMacOs()) return ScreenCaptureAccessResult.notApplicable()
        return runHelper(
            mode = "preflight",
            helperPath = helperExtractor.extract(),
            processFactory = processFactory,
            timeoutSeconds = PREFLIGHT_TIMEOUT_SECONDS,
        )
    }

    internal fun request(
        helperExtractor: HelperBinaryExtractor,
        processFactory: ProcessFactory,
        isMacOs: () -> Boolean = ::isMacOs,
    ): ScreenCaptureAccessResult {
        if (!isMacOs()) return ScreenCaptureAccessResult.notApplicable()
        // Prefer the guided SwiftUI flow (#192). If already granted, skip the window.
        val helperPath = helperExtractor.extract()
        val existing = preflight(helperExtractor, processFactory, isMacOs)
        if (existing.granted) {
            markPreviouslyGranted(helperPath)
            return existing
        }
        // Re-approval copy when a prior successful grant was recorded for this helper install.
        val reapproval = wasPreviouslyGranted(helperPath)
        val result =
            runHelper(
                mode = GUIDE_MODE,
                helperPath = helperPath,
                processFactory = processFactory,
                // Guide window blocks until the human closes it or grants permission.
                timeoutSeconds = REQUEST_TIMEOUT_SECONDS,
                extraArgs = if (reapproval) listOf("--reapproval") else emptyList(),
            )
        if (result.granted) markPreviouslyGranted(helperPath)
        return result
    }

    /**
     * Explicit legacy system-dialog path (CGRequestScreenCaptureAccess). Prefer [request] which
     * opens the guided Settings flow. Kept for tests that inject a fake helper.
     */
    internal fun requestSystemDialog(
        helperExtractor: HelperBinaryExtractor,
        processFactory: ProcessFactory,
        isMacOs: () -> Boolean = ::isMacOs,
    ): ScreenCaptureAccessResult {
        if (!isMacOs()) return ScreenCaptureAccessResult.notApplicable()
        return runHelper(
            mode = "request",
            helperPath = helperExtractor.extract(),
            processFactory = processFactory,
            timeoutSeconds = REQUEST_TIMEOUT_SECONDS,
        )
    }

    internal fun requireGranted(
        helperExtractor: HelperBinaryExtractor,
        processFactory: ProcessFactory = DefaultProcessFactory,
        isMacOs: () -> Boolean = ::isMacOs,
    ) {
        if (!isMacOs()) return
        val result = preflight(helperExtractor, processFactory, isMacOs)
        if (!result.granted) throw ScreenCaptureAccessDeniedException(result)
    }

    private fun runHelper(
        mode: String,
        helperPath: Path,
        processFactory: ProcessFactory,
        timeoutSeconds: Long,
        extraArgs: List<String> = emptyList(),
    ): ScreenCaptureAccessResult {
        val argv = buildList {
            add(helperPath.toString())
            add("--mode")
            add(mode)
            addAll(extraArgs)
        }
        val process =
            try {
                processFactory.start(argv)
            } catch (ex: IOException) {
                throw IllegalStateException(
                    "failed to start spectre-screencapture for TCC $mode: ${ex.message}",
                    ex,
                )
            }
        val (stdout, exitCode) = awaitHelper(process, mode, timeoutSeconds)
        val line =
            stdout.lineSequence().firstOrNull { it.isNotBlank() }
                ?: error("spectre-screencapture $mode produced no JSON (exit=$exitCode)")
        val parsed = parseResultJson(line, mode)
        // Exit 0 = granted, 6 = not granted; other codes are hard failures.
        check(exitCode == 0 || exitCode == HELPER_EXIT_NOT_GRANTED) {
            "spectre-screencapture $mode failed with exit=$exitCode: ${parsed.guidance}"
        }
        return if (parsed.binaryPath.isBlank()) {
            parsed.copy(binaryPath = helperPath.toString())
        } else {
            parsed
        }
    }

    private fun awaitHelper(
        process: Process,
        mode: String,
        timeoutSeconds: Long,
    ): Pair<String, Int> {
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("spectre-screencapture $mode timed out after ${timeoutSeconds}s")
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            return stdout to process.exitValue()
        } catch (ex: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while waiting for TCC $mode", ex)
        }
    }

    private fun parseResultJson(line: String, mode: String): ScreenCaptureAccessResult =
        try {
            json.decodeFromString(ScreenCaptureAccessResult.serializer(), line)
        } catch (ex: kotlinx.serialization.SerializationException) {
            throw IllegalStateException(
                "spectre-screencapture $mode returned unparseable JSON: $line",
                ex,
            )
        } catch (ex: IllegalArgumentException) {
            throw IllegalStateException(
                "spectre-screencapture $mode returned unparseable JSON: $line",
                ex,
            )
        }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    internal fun interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    private object DefaultProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process =
            ProcessBuilder(argv).redirectErrorStream(true).start()
    }

    /** Non-interactive preflight must stay snappy. */
    private const val PREFLIGHT_TIMEOUT_SECONDS: Long = 15

    /**
     * Guided permission window may stay open until a human finishes Settings. Five minutes is long
     * enough for a deliberate grant without hanging CI forever if a fake process never exits.
     */
    private const val REQUEST_TIMEOUT_SECONDS: Long = 300

    /** Helper mode for the SwiftUI guide (#192). Never used by capture fail-fast preflight. */
    internal const val GUIDE_MODE: String = "guide-permissions"

    /**
     * Sidecar next to the extracted helper executable: records that Screen Recording was once
     * granted so a later lapsed grant can open the guide with re-approval copy (#192).
     */
    private fun grantMarker(helperPath: Path): Path =
        helperPath.parent.resolve(".spectre-screen-recording-granted")

    private fun wasPreviouslyGranted(helperPath: Path): Boolean =
        try {
            Files.isRegularFile(grantMarker(helperPath))
        } catch (_: Exception) {
            false
        }

    private fun markPreviouslyGranted(helperPath: Path) {
        try {
            Files.createDirectories(helperPath.parent)
            Files.writeString(grantMarker(helperPath), "1")
        } catch (_: Exception) {
            // Best-effort; missing marker only affects copy choice, not grant itself.
        }
    }
}

/** Result of a Screen Recording TCC preflight or request. */
@Serializable
public data class ScreenCaptureAccessResult(
    public val granted: Boolean,
    @SerialName("binary") public val binaryPath: String = "",
    @SerialName("settings_path")
    public val settingsPath: String = MacOsScreenCaptureAccess.SETTINGS_PATH,
    @SerialName("deep_link") public val deepLink: String = MacOsScreenCaptureAccess.DEEP_LINK,
    public val guidance: String = "",
    public val api: String = "CGPreflightScreenCaptureAccess",
) {
    public companion object {
        public fun notApplicable(): ScreenCaptureAccessResult =
            ScreenCaptureAccessResult(
                granted = true,
                binaryPath = "",
                guidance = "Screen Recording preflight is not applicable on this platform.",
                api = "n/a",
            )
    }

    /** Multi-line text suitable for CLI/MCP relay to a human. */
    public fun relayMessage(): String =
        buildString {
                appendLine(if (granted) "Screen Recording: granted" else "Screen Recording: DENIED")
                if (binaryPath.isNotBlank()) appendLine("Binary needing grant: $binaryPath")
                appendLine("Settings: $settingsPath")
                appendLine("Deep link: $deepLink")
                if (guidance.isNotBlank()) appendLine(guidance)
            }
            .trimEnd()
}

/** Thrown when capture is refused because Screen Recording TCC is missing. */
public class ScreenCaptureAccessDeniedException(public val result: ScreenCaptureAccessResult) :
    IllegalStateException(result.relayMessage())

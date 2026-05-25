package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Strongly-typed inputs to the `spectre-screencapture` Swift helper.
 *
 * The helper's CLI contract lives at the top of
 * `recording/native/macos/Sources/SpectreScreenCapture/main.swift`. This data class is the single
 * place that translates JVM-side recording intent into the exact argv shape the helper accepts.
 * Keep it in lockstep with the Swift `Arguments.parse` body — adding a flag here without updating
 * the helper (or vice versa) will surface as `exit=2` on first use.
 *
 * Construction-time preconditions mirror the helper's own validation so callers see typed Kotlin
 * failures instead of opaque `exit=2` from the subprocess.
 */
internal data class HelperArguments(
    val mode: String = "recording",
    val source: HelperSource,
    val pid: Long? = null,
    val titleContains: String? = null,
    val region: Rectangle? = null,
    val displayIndex: Int = 0,
    val output: Path,
    val fps: Int,
    val captureCursor: Boolean,
    val discoveryTimeoutMs: Int,
) {

    init {
        require(mode == "recording" || mode == "screenshot") {
            "mode must be recording or screenshot (got $mode)"
        }
        when (source) {
            HelperSource.Window -> {
                requireNotNull(pid) { "pid is required for window capture" }
                val discriminator =
                    requireNotNull(titleContains) { "titleContains is required for window capture" }
                require(discriminator.isNotBlank()) {
                    "titleContains must be a non-blank substring; the helper rejects empty discriminators"
                }
            }
            HelperSource.Region -> {
                val captureRegion =
                    requireNotNull(region) { "region capture requires a non-empty region" }
                require(captureRegion.width > 0 && captureRegion.height > 0) {
                    "region capture requires a non-empty region; got $captureRegion"
                }
                require(captureRegion.x >= 0 && captureRegion.y >= 0) {
                    "region capture requires an origin within the selected display; got $captureRegion"
                }
                require(displayIndex >= 0) {
                    "displayIndex must be non-negative (got $displayIndex)"
                }
            }
        }
        require(fps > 0) { "fps must be positive (got $fps)" }
        require(discoveryTimeoutMs >= 0) {
            "discoveryTimeoutMs must be non-negative (got $discoveryTimeoutMs)"
        }
    }

    /**
     * Build the full argv. The helper path is argv[0] (matches `ProcessBuilder` convention) and the
     * output path is the final entry — the latter is purely a defensive convention so that a
     * misformed argv never lets the helper interpret the output path as a flag value.
     */
    fun toArgv(helperPath: Path): List<String> = buildList {
        add(helperPath.toString())
        add("--mode")
        add(mode)
        add("--source")
        add(source.cliValue)
        when (source) {
            HelperSource.Window -> {
                add("--pid")
                add(pid.toString())
                add("--title-contains")
                add(titleContains.orEmpty())
            }
            HelperSource.Region -> {
                val captureRegion = requireNotNull(region)
                add("--region")
                add(
                    listOf(
                            captureRegion.x,
                            captureRegion.y,
                            captureRegion.width,
                            captureRegion.height,
                        )
                        .joinToString(",")
                )
                add("--display-index")
                add(displayIndex.toString())
            }
        }
        add("--fps")
        add(fps.toString())
        add("--cursor")
        add(if (captureCursor) "true" else "false")
        add("--file-type")
        add(fileTypeFor(output).cliValue)
        add("--discovery-timeout-ms")
        add(discoveryTimeoutMs.toString())
        add("--output")
        add(output.toString())
    }

    private enum class RecordingFileType(val cliValue: String) {
        Mov("mov"),
        Mp4("mp4"),
    }

    private companion object {
        fun fileTypeFor(output: Path): RecordingFileType =
            when (
                output.fileName
                    ?.toString()
                    ?.substringAfterLast('.', missingDelimiterValue = "")
                    ?.lowercase()
            ) {
                "mp4" -> RecordingFileType.Mp4
                else -> RecordingFileType.Mov
            }
    }
}

internal enum class HelperSource(val cliValue: String) {
    Window("window"),
    Region("region"),
}

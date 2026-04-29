package dev.sebastiano.spectre.recording.screencapturekit

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
    val pid: Long,
    val titleContains: String,
    val output: Path,
    val fps: Int,
    val captureCursor: Boolean,
    val discoveryTimeoutMs: Int,
) {

    init {
        require(titleContains.isNotBlank()) {
            "titleContains must be a non-blank substring; the helper rejects empty discriminators"
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
        add("--pid")
        add(pid.toString())
        add("--title-contains")
        add(titleContains)
        add("--fps")
        add(fps.toString())
        add("--cursor")
        add(if (captureCursor) "true" else "false")
        add("--discovery-timeout-ms")
        add(discoveryTimeoutMs.toString())
        add("--output")
        add(output.toString())
    }
}

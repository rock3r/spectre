package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import kotlinx.serialization.Serializable

@Serializable
private data class RecordingJson(val version: Int = JSON_VERSION, val id: String, val path: String)

internal fun printRecording(
    sessionId: String,
    outputPath: String,
    json: Boolean,
    output: Appendable,
    label: String,
    diagnostics: DaemonResponse.RecordingDiagnostics? = null,
) {
    if (json && diagnostics != null) {
        output.append(
            CLI_JSON.encodeToString(
                StoppedRecordingJson(id = sessionId, path = outputPath, diagnostics = diagnostics)
            )
        )
    } else if (json)
        output.append(CLI_JSON.encodeToString(RecordingJson(id = sessionId, path = outputPath)))
    else {
        output.append("$label $outputPath.")
        diagnostics?.let { output.appendDiagnostics(it) }
    }
    output.appendLine()
}

@Serializable
private data class StoppedRecordingJson(
    val version: Int = JSON_VERSION,
    val id: String,
    val path: String,
    val diagnostics: DaemonResponse.RecordingDiagnostics,
)

internal fun Appendable.appendDiagnostics(diagnostics: DaemonResponse.RecordingDiagnostics) {
    append(" Dropped frames: ${diagnostics.droppedFrames ?: "unavailable"}.")
    append(" Crop fixed at start: ${diagnostics.cropFixedAtStart ?: "unavailable"}.")
}

package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import dev.sebastiano.spectre.cli.daemon.CaptureLifecycle
import dev.sebastiano.spectre.cli.daemon.CapturePruner
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class CapturesCommand(request: (DaemonRequest) -> DaemonResponse, output: Appendable) :
    CliktCommand(name = "captures") {
    init {
        subcommands(CapturesListCommand(request, output), CapturesPruneCommand(request, output))
    }

    override fun run(): Unit = Unit
}

private class CapturesListCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "list") {
    private val all: Boolean by option("--all").flag(default = false)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val liveIds = liveSessionIdsOrEmpty(request)
        val entries = CaptureLifecycle.ledger().listExisting()
        val rows =
            entries
                .filter { all || !it.explicitOutDir }
                .sortedByDescending { it.createdAtEpochMs }
                .map { entry ->
                    CaptureListRowJson(
                        path = entry.path,
                        sessionId = entry.sessionId,
                        sizeBytes = entry.sizeBytes,
                        createdAtEpochMs = entry.createdAtEpochMs,
                        explicitOutDir = entry.explicitOutDir,
                        live = entry.sessionId in liveIds,
                    )
                }
        if (json) {
            output.append(CAPTURE_JSON.encodeToString(CaptureListJson(captures = rows)))
        } else if (rows.isEmpty()) {
            output.append("No captures.")
            output.appendLine()
        } else {
            rows.forEach { row ->
                val status = if (row.live) "live" else "closed"
                val out = if (row.explicitOutDir) " out-dir" else ""
                output.append(
                    "${row.path}  session=${row.sessionId}  ${row.sizeBytes}B  $status$out"
                )
                output.appendLine()
            }
        }
    }
}

private class CapturesPruneCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "prune") {
    private val keep: Int? by option("--keep").int()
    private val olderThan: String? by option("--older-than")
    private val all: Boolean by option("--all").flag(default = false)
    private val sessionId: String? by option("--session")
    private val force: Boolean by option("--force").flag(default = false)
    private val includeOutDir: Boolean by
        option(
                "--include-out-dir",
                help = "Also delete captures written under client-supplied --out-dir roots",
            )
            .flag(default = false)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val older = olderThan?.let { raw ->
            parseOlderThan(raw)
                ?: throw CliktError("Invalid --older-than value: $raw (use e.g. 7d, 24h, 30m)")
        }
        val result =
            CapturePruner.prune(
                request =
                    CapturePruner.Request(
                        keep = keep,
                        olderThan = older,
                        all = all,
                        sessionId = sessionId,
                        force = force,
                        allowExplicitOutDir = includeOutDir,
                    ),
                liveSessionIds = liveSessionIdsOrEmpty(request),
            )
        if (json) {
            output.append(
                CAPTURE_JSON.encodeToString(
                    CapturePruneJson(
                        deleted = result.deletedPaths,
                        skippedLive = result.skippedLive,
                        skippedExplicitOutDir = result.skippedExplicitOutDir,
                    )
                )
            )
        } else {
            output.append("Deleted ${result.deletedPaths.size} capture(s).")
            if (result.skippedLive.isNotEmpty()) {
                output.appendLine()
                output.append(
                    "Skipped ${result.skippedLive.size} live-session capture(s) (use --force)."
                )
            }
            if (result.skippedExplicitOutDir.isNotEmpty()) {
                output.appendLine()
                output.append(
                    "Skipped ${result.skippedExplicitOutDir.size} out-dir capture(s) " +
                        "(use --include-out-dir)."
                )
            }
        }
        output.appendLine()
    }
}

internal fun liveSessionIdsOrEmpty(request: (DaemonRequest) -> DaemonResponse): Set<String> =
    runCatching {
            when (val response = request(DaemonRequest.ListSessions)) {
                is DaemonResponse.Sessions -> response.sessions.map { it.sessionId }.toSet()
                else -> emptySet()
            }
        }
        .getOrDefault(emptySet())

internal fun parseOlderThan(raw: String): Duration? {
    val match = Regex("^(\\d+)([smhd])$").matchEntire(raw.trim()) ?: return null
    val amount = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "s" -> Duration.ofSeconds(amount)
        "m" -> Duration.ofMinutes(amount)
        "h" -> Duration.ofHours(amount)
        "d" -> Duration.ofDays(amount)
        else -> null
    }
}

internal fun formatCaptureMegabytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.2f", bytes / BYTES_PER_MEBIBYTE)

private const val BYTES_PER_MEBIBYTE: Double = 1024.0 * 1024.0

@Serializable
internal data class CaptureListJson(val version: Int = 1, val captures: List<CaptureListRowJson>)

@Serializable
internal data class CaptureListRowJson(
    val path: String,
    val sessionId: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
    val explicitOutDir: Boolean,
    val live: Boolean,
)

@Serializable
internal data class CapturePruneJson(
    val version: Int = 1,
    val deleted: List<String>,
    val skippedLive: List<String>,
    val skippedExplicitOutDir: List<String>,
)

private val CAPTURE_JSON: Json = Json { encodeDefaults = true }

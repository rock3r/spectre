package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/** Explicit prune for `spectre captures prune` — all deletion flows through here. */
internal object CapturePruner {
    data class Request(
        val keep: Int? = null,
        val olderThan: Duration? = null,
        val all: Boolean = false,
        val sessionId: String? = null,
        val force: Boolean = false,
        val allowExplicitOutDir: Boolean = false,
    )

    data class Result(
        val deletedPaths: List<String>,
        val skippedLive: List<String>,
        val skippedExplicitOutDir: List<String>,
    )

    fun prune(
        request: Request,
        ledger: CaptureLedger = CaptureLifecycle.ledger(),
        liveSessionIds: Set<String>,
        clock: Clock = Clock.systemUTC(),
    ): Result {
        require(
            request.keep != null ||
                request.olderThan != null ||
                request.all ||
                request.sessionId != null
        ) {
            "prune requires --keep, --older-than, --all, or --session"
        }
        require(request.keep == null || request.keep >= 0) {
            "--keep must be zero or a positive integer"
        }
        val candidates = candidatesFor(request, ledger)
        val classified = classify(candidates, request, liveSessionIds)
        val toDelete = selectForDeletion(classified.selected, request, clock.millis())
        val deleted = deleteEntries(toDelete, ledger)
        return Result(
            deletedPaths = deleted,
            skippedLive = classified.skippedLive,
            skippedExplicitOutDir = classified.skippedExplicit,
        )
    }

    private fun candidatesFor(request: Request, ledger: CaptureLedger): List<CaptureLedgerEntry> =
        ledger.listExisting().filter { entry ->
            request.sessionId == null || entry.sessionId == request.sessionId
        }

    private data class Classified(
        val selected: List<CaptureLedgerEntry>,
        val skippedLive: List<String>,
        val skippedExplicit: List<String>,
    )

    private fun classify(
        candidates: List<CaptureLedgerEntry>,
        request: Request,
        liveSessionIds: Set<String>,
    ): Classified {
        val selected = ArrayList<CaptureLedgerEntry>()
        val skippedLive = ArrayList<String>()
        val skippedExplicit = ArrayList<String>()
        for (entry in candidates) {
            when {
                !request.force && entry.sessionId in liveSessionIds -> skippedLive.add(entry.path)
                entry.explicitOutDir && !request.allowExplicitOutDir ->
                    skippedExplicit.add(entry.path)
                else -> selected.add(entry)
            }
        }
        return Classified(selected, skippedLive, skippedExplicit)
    }

    private fun selectForDeletion(
        selected: List<CaptureLedgerEntry>,
        request: Request,
        nowEpochMs: Long,
    ): List<CaptureLedgerEntry> {
        val sorted = selected.sortedBy { it.createdAtEpochMs }
        if (request.all) return sorted
        if (request.sessionId != null && request.keep == null && request.olderThan == null) {
            return sorted
        }
        var remaining = sorted
        request.olderThan?.let { age ->
            val cutoff = nowEpochMs - age.toMillis()
            remaining = remaining.filter { it.createdAtEpochMs < cutoff }
        }
        request.keep?.let { keep ->
            remaining = if (remaining.size > keep) remaining.dropLast(keep) else emptyList()
        }
        return remaining
    }

    private fun deleteEntries(
        entries: List<CaptureLedgerEntry>,
        ledger: CaptureLedger,
    ): List<String> {
        val deleted = ArrayList<String>()
        for (entry in entries) {
            if (CaptureRetention.deleteCaptureDirectory(Path.of(entry.path))) {
                deleted.add(entry.path)
            }
        }
        if (deleted.isNotEmpty()) {
            ledger.removePaths(deleted.toSet())
        }
        return deleted
    }
}

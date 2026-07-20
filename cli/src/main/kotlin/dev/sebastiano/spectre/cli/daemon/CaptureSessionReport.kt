package dev.sebastiano.spectre.cli.daemon

/** Session-scoped leftover capture reporting for detach. */
internal object CaptureSessionReport {
    fun forDetach(
        sessionId: String,
        ledger: CaptureLedger = CaptureLifecycle.ledger(),
    ): DaemonResponse.Detached {
        val entries = ledger.entriesForSession(sessionId)
        val bytes = entries.sumOf { it.sizeBytes }
        val paths = entries.map { it.path }
        return DaemonResponse.Detached(
            sessionId = sessionId,
            captureCount = entries.size,
            captureBytes = bytes,
            capturePaths = paths,
            pruneCommand =
                if (entries.isEmpty()) {
                    null
                } else {
                    "spectre captures prune --session $sessionId"
                },
        )
    }
}

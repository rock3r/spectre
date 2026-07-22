package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonResponse

/** Maps a [ReloadSettleOutcome] onto the daemon wire response for `waitForReloadSettled` (#211). */
internal fun mapReloadSettleOutcome(
    outcome: ReloadSettleOutcome,
    sessionId: String,
    timeoutMs: Long,
): DaemonResponse =
    when (outcome) {
        ReloadSettleOutcome.Settled -> DaemonResponse.Completed(sessionId)
        ReloadSettleOutcome.Unavailable ->
            DaemonResponse.Error(
                code = DaemonErrorCode.HotReloadUnavailable,
                message = "hot reload is not available for this session",
                category = ReloadSettleErrorCategory.HOT_RELOAD_UNAVAILABLE,
            )
        is ReloadSettleOutcome.ReloadFailed ->
            DaemonResponse.Error(
                code = DaemonErrorCode.ReloadFailed,
                message = "reload failed: ${outcome.errorMessage}",
                category = ReloadSettleErrorCategory.RELOAD_FAILED,
            )
        ReloadSettleOutcome.TimedOut ->
            DaemonResponse.Error(
                code = DaemonErrorCode.Timeout,
                message = "timed out waiting for reload to settle after ${timeoutMs}ms",
                category = ReloadSettleErrorCategory.TIMEOUT,
            )
        ReloadSettleOutcome.Cancelled ->
            DaemonResponse.Error(
                code = DaemonErrorCode.OperationFailed,
                message = "reload settle wait was cancelled",
                category = ReloadSettleErrorCategory.CANCELLED,
            )
    }

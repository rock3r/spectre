package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAgentException
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory

/**
 * Runs `waitUntilGone` for one session and maps its outcome onto the daemon wire (#438).
 *
 * Kept out of [DaemonSessionRegistry]'s generic `IOException` arm on purpose. That arm answers
 * every session op with `operationFailed` and no category, which is the right default but the wrong
 * answer here: an absence wait's whole value is its failure message, which names the selector, the
 * timeout, and how many matching nodes were still present in tracked windows. Flattening that — and
 * the #199 `timeout` taxonomy that lets a caller tell "still on screen" from "the session died" —
 * would leave remote callers with strictly less than the in-process API gives them.
 *
 * Lives beside
 * [mapReloadSettleOutcome][dev.sebastiano.spectre.cli.hotreload.mapReloadSettleOutcome] in shape:
 * one op's outcome, one response mapping, its own file.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun waitUntilGoneResponse(
    automator: DaemonSessionAutomator,
    request: DaemonRequest.WaitUntilGone,
): DaemonResponse =
    try {
        automator.waitUntilGone(
            tag = request.tag,
            text = request.text,
            timeoutMs = request.timeoutMs,
            pollIntervalMs = request.pollIntervalMs,
        )
        DaemonResponse.Completed(request.sessionId)
    } catch (exception: SpectreAgentException) {
        DaemonResponse.Error(
            code = daemonErrorCodeFor(exception.category),
            // Verbatim: the agent already composed the diagnostics, and rewording them here would
            // be the exact degradation this mapping exists to prevent.
            message = exception.message ?: "waitUntilGone failed",
            category = exception.category.wireName,
        )
    }

/**
 * Maps a #199 agent taxonomy category onto the daemon's own error code.
 *
 * Only the categories a wait can produce get a dedicated code; everything else stays
 * [DaemonErrorCode.OperationFailed] while the precise taxonomy still travels in
 * [DaemonResponse.Error.category].
 */
@OptIn(ExperimentalSpectreAgentApi::class)
private fun daemonErrorCodeFor(category: AgentErrorCategory): DaemonErrorCode =
    when (category) {
        AgentErrorCategory.Timeout -> DaemonErrorCode.Timeout
        else -> DaemonErrorCode.OperationFailed
    }

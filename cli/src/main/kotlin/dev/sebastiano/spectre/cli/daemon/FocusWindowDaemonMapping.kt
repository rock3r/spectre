package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory

/**
 * Raises and focuses the window at [DaemonRequest.FocusWindow.windowIndex] (#556).
 *
 * The agent focuses the window that hosts a node, while CLI and MCP callers think in window indices
 * (the ones `spectre windows` prints). Node keys are `surfaceId:ownerIndex:nodeId`, so any node
 * whose key starts with the window's surface id lives in that window; the first one is used.
 *
 * An index the session does not have is an `invalidSelector`; a window with no semantics nodes yet
 * is a `nodeNotFound`. Both say so in the message rather than falling through to a bare
 * `operationFailed`.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun focusWindowResponse(
    automator: DaemonSessionAutomator,
    request: DaemonRequest.FocusWindow,
): DaemonResponse {
    val windows = automator.windows()
    val window =
        windows.firstOrNull { it.index == request.windowIndex }
            ?: return DaemonResponse.Error(
                code = DaemonErrorCode.OperationFailed,
                message =
                    "No window at index ${request.windowIndex}; the session has " +
                        "${windows.size} window(s). Run `spectre windows` to list them.",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
    val surfacePrefix = "${window.surfaceId}:"
    val nodeKey =
        automator.allNodes().firstOrNull { it.key.startsWith(surfacePrefix) }?.key
            ?: return DaemonResponse.Error(
                code = DaemonErrorCode.OperationFailed,
                message =
                    "Window ${request.windowIndex} (${window.surfaceId}) has no semantics nodes " +
                        "to focus it through.",
                category = AgentErrorCategory.NodeNotFound.wireName,
            )
    automator.focusWindow(nodeKey)
    return DaemonResponse.Completed(request.sessionId)
}

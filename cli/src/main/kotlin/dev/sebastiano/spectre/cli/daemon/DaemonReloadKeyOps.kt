package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.cli.hotreload.ReloadAwareKeyGuard

/**
 * File-level helpers for reload-aware node-key invalidation (#212), kept out of
 * [DaemonSessionRegistry] to satisfy TooManyFunctions.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun rejectStaleNodeKey(
    keyGuard: ReloadAwareKeyGuard?,
    nodeKey: String?,
): DaemonResponse.Error? {
    if (nodeKey == null || keyGuard == null) return null
    if (keyGuard.accepts(nodeKey)) return null
    return DaemonResponse.Error(
        code = DaemonErrorCode.OperationFailed,
        message = "No node found with key=$nodeKey",
        category = "nodeNotFound",
    )
}

@OptIn(ExperimentalSpectreAgentApi::class)
internal fun rememberNodeKeys(keyGuard: ReloadAwareKeyGuard?, nodes: List<NodeSnapshotDto>) {
    keyGuard?.rememberIssuedKeys(nodes.map(NodeSnapshotDto::key))
}

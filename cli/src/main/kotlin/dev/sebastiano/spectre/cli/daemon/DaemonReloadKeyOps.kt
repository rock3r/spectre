package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.cli.hotreload.ReloadAwareKeyGuard

/** Issue generation-stamped keys for a tree/find response (#212). */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun issueNodeKeys(
    keyGuard: ReloadAwareKeyGuard?,
    nodes: List<NodeSnapshotDto>,
): List<NodeSnapshotDto> = if (keyGuard == null) nodes else keyGuard.issueNodes(nodes)

/** Issue a single stamped key (#212). */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun issueNodeKey(keyGuard: ReloadAwareKeyGuard?, node: NodeSnapshotDto): NodeSnapshotDto =
    if (keyGuard == null) node else keyGuard.issueNode(node)

/** Resolve a client key for agent dispatch. Returns an error when the key is stale after reload. */
internal fun resolveNodeKeyForDispatch(
    keyGuard: ReloadAwareKeyGuard?,
    nodeKey: String?,
): KeyResolution {
    if (nodeKey == null) return KeyResolution.Missing
    if (keyGuard == null) return KeyResolution.Raw(nodeKey)
    val raw = keyGuard.resolveForDispatch(nodeKey)
    return if (raw == null) {
        KeyResolution.Stale(
            DaemonResponse.Error(
                code = DaemonErrorCode.OperationFailed,
                message = "No node found with key=$nodeKey",
                category = "nodeNotFound",
            )
        )
    } else {
        KeyResolution.Raw(raw)
    }
}

internal sealed interface KeyResolution {
    data object Missing : KeyResolution

    data class Raw(val key: String) : KeyResolution

    data class Stale(val error: DaemonResponse.Error) : KeyResolution
}

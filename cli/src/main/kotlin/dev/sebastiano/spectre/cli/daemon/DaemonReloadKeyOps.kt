package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.cli.hotreload.ReloadAwareKeyGuard

/** Query the automator and stamp keys, re-querying once if a reload settles mid-query (#212). */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun issueNodesAcrossReload(
    keyGuard: ReloadAwareKeyGuard?,
    query: () -> List<NodeSnapshotDto>,
): List<NodeSnapshotDto> {
    if (keyGuard == null) return query()
    val gen1 = keyGuard.snapshotGeneration()
    val nodes1 = query()
    keyGuard.issueNodesIfGeneration(gen1, nodes1)?.let {
        return it
    }
    // Reload settled during the query — re-read under the new generation.
    val gen2 = keyGuard.snapshotGeneration()
    val nodes2 = query()
    return keyGuard.issueNodesIfGeneration(gen2, nodes2)
        ?: keyGuard.issueNodes(nodes2) // generation stable after re-query
}

@OptIn(ExperimentalSpectreAgentApi::class)
internal fun issueNodeAcrossReload(
    keyGuard: ReloadAwareKeyGuard?,
    query: () -> NodeSnapshotDto,
): NodeSnapshotDto = issueNodesAcrossReload(keyGuard) { listOf(query()) }.single()

internal fun staleNodeKeyError(nodeKey: String): DaemonResponse.Error =
    DaemonResponse.Error(
        code = DaemonErrorCode.OperationFailed,
        message = "No node found with key=$nodeKey",
        category = "nodeNotFound",
    )

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.cli.hotreload.ReloadAwareKeyGuard

/**
 * Query the automator and stamp keys, re-querying while a reload settles mid-query (#212).
 *
 * Never stamps a snapshot under a generation that advanced after the query — loops until the
 * generation is stable or the attempt budget is exhausted.
 *
 * @return stamped nodes, or `null` when the generation kept racing through [maxAttempts] (caller
 *   must fail closed — do not report an empty tree as success).
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun issueNodesAcrossReload(
    keyGuard: ReloadAwareKeyGuard?,
    maxAttempts: Int = 4,
    query: () -> List<NodeSnapshotDto>,
): List<NodeSnapshotDto>? {
    if (keyGuard == null) return query()
    repeat(maxAttempts) {
        val gen = keyGuard.snapshotGeneration()
        val nodes = query()
        val stamped = keyGuard.issueNodesIfGeneration(gen, nodes)
        if (stamped != null) return stamped
    }
    // Generation kept racing — fail closed rather than returning a false empty tree.
    return null
}

/**
 * Stamp a single node that was already obtained (e.g. after waitForNode). Does **not** re-run the
 * wait — if a reload raced the wait, returns null so the caller can fail closed.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun stampNodeIfCurrentGeneration(
    keyGuard: ReloadAwareKeyGuard?,
    expectedGeneration: Long,
    node: NodeSnapshotDto,
): NodeSnapshotDto? {
    if (keyGuard == null) return node
    return keyGuard.issueNodesIfGeneration(expectedGeneration, listOf(node))?.single()
}

internal fun staleNodeKeyError(nodeKey: String): DaemonResponse.Error =
    DaemonResponse.Error(
        code = DaemonErrorCode.OperationFailed,
        message = "No node found with key=$nodeKey",
        category = "nodeNotFound",
    )

/** Fail-closed error when [issueNodesAcrossReload] exhausts its generation-race budget. */
internal fun reloadRaceExhaustedError(): DaemonResponse.Error =
    DaemonResponse.Error(
        code = DaemonErrorCode.OperationFailed,
        message = "reload settled during tree query; re-query required",
        category = "reloadRace",
    )

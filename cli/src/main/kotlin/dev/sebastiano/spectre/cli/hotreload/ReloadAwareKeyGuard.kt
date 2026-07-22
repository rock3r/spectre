package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto

/**
 * Tracks node keys issued to clients for a reload-aware session (#212).
 *
 * Keys returned to clients are **generation-stamped** (`g{n}:{rawKey}`). After a hot reload the
 * generation advances and the allowlist is cleared, so pre-reload stamped keys fail closed as
 * `nodeNotFound`. Dispatch holds the same lock as [onReload] so a settle cannot race a click
 * between validation and IPC.
 *
 * Non-reload-aware sessions do not use this guard.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
public class ReloadAwareKeyGuard {
    private val lock = Any()
    private var generation: Long = 0L
    /** Raw (unstamped) keys issued in [generation]; null means none issued yet this generation. */
    private var issuedKeys: Set<String>? = null

    /** Current generation (for pre-query snapshots). */
    public fun snapshotGeneration(): Long = synchronized(lock) { generation }

    /**
     * Records raw keys under [expectedGeneration] (must still match live generation), unions with
     * existing keys in that generation, and returns nodes with stamped keys. Returns null when the
     * generation changed mid-query (caller should re-query).
     */
    public fun issueNodesIfGeneration(
        expectedGeneration: Long,
        nodes: List<NodeSnapshotDto>,
    ): List<NodeSnapshotDto>? {
        synchronized(lock) {
            if (expectedGeneration != generation) return null
            val rawKeys = nodes.map { it.key }
            val prior = issuedKeys
            issuedKeys = if (prior == null) rawKeys.toSet() else prior + rawKeys
            val gen = generation
            return nodes.map { node -> node.copy(key = stamp(gen, node.key)) }
        }
    }

    /** Issues nodes under the current generation (no mid-query race protection). */
    public fun issueNodes(nodes: List<NodeSnapshotDto>): List<NodeSnapshotDto> {
        synchronized(lock) {
            return issueNodesIfGeneration(generation, nodes)
                ?: error("generation advanced during locked issueNodes")
        }
    }

    public fun issueNode(node: NodeSnapshotDto): NodeSnapshotDto = issueNodes(listOf(node)).single()

    /** Advances generation and clears issued keys after a successful reload settle. */
    public fun onReload() {
        synchronized(lock) {
            generation += 1
            issuedKeys = null
        }
    }

    /**
     * Validates [stampedKey] and runs [op] with the raw key under the same lock as [onReload], so a
     * settle cannot interleave between validation and dispatch.
     *
     * @return false when the key is stale / unknown (caller should return nodeNotFound).
     */
    public fun dispatch(stampedKey: String, op: (rawKey: String) -> Unit): Boolean {
        synchronized(lock) {
            val raw = resolveUnlocked(stampedKey) ?: return false
            op(raw)
            return true
        }
    }

    public fun resolveForDispatch(stampedKey: String): String? =
        synchronized(lock) { resolveUnlocked(stampedKey) }

    public fun accepts(stampedKey: String): Boolean = resolveForDispatch(stampedKey) != null

    public fun isInvalidated(): Boolean =
        synchronized(lock) { issuedKeys == null && generation > 0L }

    public fun generation(): Long = synchronized(lock) { generation }

    private fun resolveUnlocked(stampedKey: String): String? {
        val parsed = unstamp(stampedKey)
        if (parsed == null) {
            // Unstamped keys: only before any reload and before any tree (legacy first-click).
            if (generation != 0L || issuedKeys != null) return null
            return stampedKey
        }
        val (gen, raw) = parsed
        if (gen != generation) return null
        val known = issuedKeys
        // After reload (or before first tree in gen>0), reject everything until a tree is issued.
        if (known == null) return null
        if (raw !in known) return null
        return raw
    }

    public companion object {
        internal fun stamp(generation: Long, rawKey: String): String = "g$generation:$rawKey"

        internal fun unstamp(stampedKey: String): Pair<Long, String>? {
            if (!stampedKey.startsWith("g")) return null
            val colon = stampedKey.indexOf(':')
            if (colon <= 1) return null
            val gen = stampedKey.substring(1, colon).toLongOrNull() ?: return null
            val raw = stampedKey.substring(colon + 1)
            if (raw.isEmpty()) return null
            return gen to raw
        }
    }
}

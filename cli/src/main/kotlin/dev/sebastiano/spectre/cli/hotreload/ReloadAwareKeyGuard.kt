package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto

/**
 * Tracks node keys issued to clients for a reload-aware session (#212).
 *
 * Keys returned to clients are **generation-stamped** (`g{n}:{rawKey}`). After a hot reload the
 * generation advances, so pre-reload stamped keys fail closed as `nodeNotFound` even if Compose
 * reuses node ids. Dispatch re-validates the stamp under the guard lock so a settle cannot race a
 * click between check and IPC.
 *
 * Non-reload-aware sessions do not use this guard.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
public class ReloadAwareKeyGuard {
    private val lock = Any()
    private var generation: Long = 0L
    /** Raw (unstamped) keys issued in [generation]. */
    private var issuedKeys: Set<String>? = null

    /**
     * Records raw keys from a tree/find under the current generation and returns nodes with stamped
     * keys for the client. Unions with keys already issued in this generation.
     */
    public fun issueNodes(nodes: List<NodeSnapshotDto>): List<NodeSnapshotDto> {
        synchronized(lock) {
            val rawKeys = nodes.map { it.key }
            val prior = issuedKeys
            issuedKeys = if (prior == null) rawKeys.toSet() else prior + rawKeys
            val gen = generation
            return nodes.map { node -> node.copy(key = stamp(gen, node.key)) }
        }
    }

    /** Issues a single node key (e.g. waitForNode result) under the current generation. */
    public fun issueNode(node: NodeSnapshotDto): NodeSnapshotDto = issueNodes(listOf(node)).single()

    /** Advances generation and clears issued keys after a successful reload settle. */
    public fun onReload() {
        synchronized(lock) {
            generation += 1
            issuedKeys = null
        }
    }

    /**
     * Validates a client [stampedKey] and returns the raw key for agent dispatch, or `null` if the
     * key is stale / unknown.
     *
     * When [issuedKeys] is still null in generation 0 (no tree yet), plain unstamped keys are
     * accepted for compatibility with first-click-without-tree flows.
     */
    public fun resolveForDispatch(stampedKey: String): String? {
        synchronized(lock) {
            val parsed = unstamp(stampedKey)
            if (parsed == null) {
                // Unstamped: only allow before any reload and before any tree (legacy path).
                if (generation != 0L || issuedKeys != null) return null
                return stampedKey
            }
            val (gen, raw) = parsed
            if (gen != generation) return null
            val known = issuedKeys
            if (known != null && raw !in known) return null
            return raw
        }
    }

    /** @deprecated Prefer [resolveForDispatch]; kept for unit tests of stamp semantics. */
    public fun accepts(stampedKey: String): Boolean = resolveForDispatch(stampedKey) != null

    public fun isInvalidated(): Boolean =
        synchronized(lock) { issuedKeys == null && generation > 0L }

    public fun generation(): Long = synchronized(lock) { generation }

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

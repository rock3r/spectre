package dev.sebastiano.spectre.cli.hotreload

/**
 * Tracks node keys issued to clients for a reload-aware session (#212).
 *
 * After a hot reload, keys captured from pre-reload tree dumps are rejected with a defined
 * `nodeNotFound` failure instead of being resolved against a post-reload tree that may reuse
 * Compose node ids for different content.
 *
 * Non-reload-aware sessions do not use this guard.
 */
public class ReloadAwareKeyGuard {
    private val lock = Any()
    /** Bumped on each [onReload]; only keys remembered for the current generation are accepted. */
    private var generation: Long = 0L
    /**
     * Keys issued in [generation]. `null` means no tree has been issued in this generation yet —
     * key ops still go to the live agent (same as pre-#212 behaviour for the first tree).
     */
    private var issuedKeys: Set<String>? = null

    /** Records keys from a tree/find response for the current generation (unioning). */
    public fun rememberIssuedKeys(keys: Collection<String>) {
        synchronized(lock) {
            val existing = issuedKeys
            issuedKeys =
                if (existing == null) {
                    keys.toSet()
                } else {
                    existing + keys
                }
        }
    }

    /**
     * Marks the session as post-reload: subsequent [accepts] fail until [rememberIssuedKeys] runs
     * for the new generation.
     */
    public fun onReload() {
        synchronized(lock) {
            generation += 1
            issuedKeys = null
        }
    }

    /**
     * Returns true when [key] may be forwarded to the agent. False means the daemon should fail
     * closed as `nodeNotFound` without contacting the target.
     */
    public fun accepts(key: String): Boolean {
        synchronized(lock) {
            val known = issuedKeys
            // After reload and before the next tree, reject everything.
            if (known == null) return generation == 0L
            return key in known
        }
    }

    /** Test/diagnostics: whether a reload has invalidated keys awaiting a fresh tree. */
    public fun isInvalidated(): Boolean =
        synchronized(lock) { issuedKeys == null && generation > 0 }

    /** Test/diagnostics: current generation after reloads. */
    public fun generation(): Long = synchronized(lock) { generation }
}

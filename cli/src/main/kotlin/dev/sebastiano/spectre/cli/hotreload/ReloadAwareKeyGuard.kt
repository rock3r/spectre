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
    private var state: KeyState = KeyState.Untracked

    /** Records keys from a tree/find response and clears the post-reload invalidation flag. */
    public fun rememberIssuedKeys(keys: Collection<String>) {
        synchronized(lock) { state = KeyState.Tracked(keys.toSet()) }
    }

    /**
     * Marks the session as post-reload: subsequent [accepts] calls fail until [rememberIssuedKeys]
     * runs again.
     */
    public fun onReload() {
        synchronized(lock) { state = KeyState.Invalidated }
    }

    /**
     * Returns true when [key] may be forwarded to the agent. False means the daemon should fail
     * closed as `nodeNotFound` without contacting the target.
     */
    public fun accepts(key: String): Boolean {
        synchronized(lock) {
            return when (val current = state) {
                KeyState.Untracked -> true
                KeyState.Invalidated -> false
                is KeyState.Tracked -> key in current.keys
            }
        }
    }

    /** Test/diagnostics: whether a reload has invalidated keys awaiting a fresh tree. */
    public fun isInvalidated(): Boolean = synchronized(lock) { state is KeyState.Invalidated }

    private sealed interface KeyState {
        /** No tree has been issued yet — key ops still go to the live agent. */
        data object Untracked : KeyState

        /** Post-reload: every key is rejected until the next tree/find. */
        data object Invalidated : KeyState

        /** Keys from the last tree/find after attach or post-reload. */
        data class Tracked(val keys: Set<String>) : KeyState
    }
}

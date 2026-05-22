package dev.sebastiano.spectre.core

import java.lang.ref.WeakReference

/**
 * Assigns stable, human-readable surface ids of the form `"$prefix:$index"`.
 *
 * Identity is compared **by reference** across all [identity] parts so that two objects which
 * happen to be `equals` (e.g. two `String` instances with the same content) still get distinct ids
 * — what matters for a surface is the underlying object handle, not its value. Composite identities
 * (e.g. `(window, composePanel)`) are supported via the vararg: every part must be the same
 * reference for a cache hit.
 *
 * Indices grow monotonically per prefix; a surface that goes away does not have its index recycled,
 * so the same numeric suffix never refers to two different surfaces within the assigner's lifetime.
 *
 * Identity parts are held via [WeakReference] so a closed window or disposed panel is not retained
 * by this cache. On every [assign] call we prune entries whose any non-null part has been
 * garbage-collected — keeps the cache footprint bounded in long-running sessions that churn through
 * many transient popups.
 */
@InternalSpectreApi
public class SurfaceIdAssigner {

    private val nextIndex = mutableMapOf<String, Int>()
    private val cache = mutableMapOf<CompositeIdentity, String>()

    public fun assign(prefix: String, vararg identity: Any?): String {
        pruneCollected()
        val parts = Array<IdentityRef?>(identity.size) { i -> identity[i]?.let(::IdentityRef) }
        val key = CompositeIdentity(prefix, parts)
        cache[key]?.let {
            return it
        }
        val index = nextIndex.getOrDefault(prefix, 0)
        nextIndex[prefix] = index + 1
        val id = "$prefix:$index"
        cache[key] = id
        return id
    }

    private fun pruneCollected() {
        // O(cache size) per call but cache size is bounded by the number of surfaces ever seen
        // within an automator lifetime — typically tens, rarely hundreds. Avoiding the prune
        // would mean either a separately scheduled cleanup task (more moving parts) or letting
        // the cache grow unbounded across long sessions.
        cache.keys.removeAll { it.anyCollected() }
    }

    /**
     * Reference-equality wrapper around an identity part. Holds the referent weakly so this cache
     * never keeps a window/panel alive past its real lifetime, while a stable [identityHashCode]
     * captured at construction keeps hashes consistent across cache lookups even before the
     * referent is collected.
     */
    private class IdentityRef(referent: Any) {
        private val ref = WeakReference(referent)
        private val identityHashCode = System.identityHashCode(referent)

        fun isCollected(): Boolean = ref.get() == null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityRef) return false
            val thisReferent = ref.get() ?: return false
            val otherReferent = other.ref.get() ?: return false
            return thisReferent === otherReferent
        }

        override fun hashCode(): Int = identityHashCode
    }

    private class CompositeIdentity(
        val prefix: String,
        private val parts: Array<out IdentityRef?>,
    ) {

        fun anyCollected(): Boolean = parts.any { it != null && it.isCollected() }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CompositeIdentity) return false
            if (prefix != other.prefix) return false
            if (parts.size != other.parts.size) return false
            for (i in parts.indices) {
                val left = parts[i]
                val right = other.parts[i]
                if (left == null && right == null) continue
                if (left == null || right == null) return false
                if (left != right) return false
            }
            return true
        }

        override fun hashCode(): Int {
            var hash = prefix.hashCode()
            for (part in parts) {
                hash = HASH_MULTIPLIER * hash + (part?.hashCode() ?: 0)
            }
            return hash
        }
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}

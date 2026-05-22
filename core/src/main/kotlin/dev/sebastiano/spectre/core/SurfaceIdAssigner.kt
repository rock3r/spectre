package dev.sebastiano.spectre.core

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
 */
@InternalSpectreApi
public class SurfaceIdAssigner {

    private val nextIndex = mutableMapOf<String, Int>()
    private val cache = mutableMapOf<CompositeIdentity, String>()

    public fun assign(prefix: String, vararg identity: Any?): String {
        val key = CompositeIdentity(prefix, identity)
        cache[key]?.let {
            return it
        }
        val index = nextIndex.getOrDefault(prefix, 0)
        nextIndex[prefix] = index + 1
        val id = "$prefix:$index"
        cache[key] = id
        return id
    }

    private class CompositeIdentity(val prefix: String, private val parts: Array<out Any?>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CompositeIdentity) return false
            if (prefix != other.prefix) return false
            if (parts.size != other.parts.size) return false
            for (i in parts.indices) {
                if (parts[i] !== other.parts[i]) return false
            }
            return true
        }

        override fun hashCode(): Int {
            var hash = prefix.hashCode()
            for (part in parts) {
                hash =
                    HASH_MULTIPLIER * hash +
                        (if (part == null) 0 else System.identityHashCode(part))
            }
            return hash
        }
    }

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}

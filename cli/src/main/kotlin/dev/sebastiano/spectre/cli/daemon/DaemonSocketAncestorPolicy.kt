package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides whether an existing POSIX ancestor of the daemon socket directory is safe to build on.
 *
 * An ancestor must be owned by `root` or the current user, and must be either sticky (like `/tmp`)
 * or not writable by group or others. The daemon's own directory is checked separately and much
 * more strictly (owner-only, see `DaemonSocketProtection`).
 *
 * Linux user namespaces add one trusted owner. Rootless containers and sandboxes (bubblewrap,
 * Bazel, cloud dev containers) often leave host `root` unmapped, so host-owned directories such as
 * `/` and `/tmp` appear owned by the kernel overflow uid (`nobody`, 65534). That uid is trusted
 * only while this process runs in a non-initial user namespace that leaves it unmapped: then no
 * process inside the namespace can hold it. When the namespace maps the overflow uid to a real
 * user, ownership is ambiguous and stays untrusted.
 *
 * The overflow uid also stands for every other unmapped host user, whose own sticky directory would
 * look the same and who could rename the daemon's directory inside it. The exception therefore
 * covers only `/` and `/tmp`, which an ordinary host user cannot own.
 */
internal object DaemonSocketAncestorPolicy {
    /** The overflow uid unmapped in this process's user namespace, or `null` if none applies. */
    val currentUnmappedOverflowUid: Int? by lazy {
        unmappedOverflowUid(readOrNull(UID_MAP_PATH), readOrNull(OVERFLOW_UID_PATH))
    }

    /** Returns why [directory] cannot be a socket ancestor, or `null` when it can. */
    fun rejection(
        directory: Path,
        ownerName: String,
        ownerUid: Int?,
        mode: Int,
        currentUser: String,
        unmappedOverflowUid: Int? = currentUnmappedOverflowUid,
    ): String? {
        val trustedOwner =
            ownerName == "root" ||
                ownerName == currentUser ||
                (unmappedOverflowUid != null &&
                    ownerUid == unmappedOverflowUid &&
                    directory in OVERFLOW_OWNER_PATHS)
        return when {
            !trustedOwner -> "Daemon socket ancestor $directory must be owned by root or user"
            mode and STICKY_BIT != 0 -> null
            mode and GROUP_OR_OTHER_WRITE_BITS == 0 -> null
            else -> "Daemon socket ancestor $directory must not be group or world writable"
        }
    }

    /**
     * Parses `/proc/self/uid_map` and `/proc/sys/kernel/overflowuid`. Returns the overflow uid when
     * the map is not the initial namespace's identity mapping and no mapped range covers it.
     */
    fun unmappedOverflowUid(uidMap: String?, overflowUid: String?): Int? {
        val overflow = overflowUid?.trim()?.toIntOrNull() ?: return null
        val ranges =
            uidMap
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?.map { line -> line.trim().split(WHITESPACE).map(String::toLongOrNull) }
                ?: return null
        if (ranges.isEmpty() || ranges.any { it.size != UID_MAP_FIELDS || null in it }) return null
        val parsed = ranges.map { fields -> fields.map { it ?: 0L } }
        val isInitialNamespace =
            parsed.size == 1 && parsed[0] == listOf(0L, 0L, INITIAL_NAMESPACE_RANGE)
        if (isInitialNamespace) return null
        val overflowIsMapped = parsed.any { (inside, _, count) ->
            overflow >= inside && overflow < inside + count
        }
        return if (overflowIsMapped) null else overflow
    }

    private fun readOrNull(path: String): String? = runCatching {
        Files.readString(Path.of(path))
    }
        .getOrNull()

    private const val UID_MAP_PATH = "/proc/self/uid_map"
    private const val OVERFLOW_UID_PATH = "/proc/sys/kernel/overflowuid"
    private const val UID_MAP_FIELDS = 3
    private const val INITIAL_NAMESPACE_RANGE = 4_294_967_295L
    private const val STICKY_BIT: Int = 0x200
    private const val GROUP_OR_OTHER_WRITE_BITS: Int = 0x12
    private val WHITESPACE = Regex("\\s+")
    private val OVERFLOW_OWNER_PATHS = setOf(Path.of("/"), Path.of("/tmp"))
}

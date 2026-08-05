package dev.sebastiano.spectre.build

/**
 * Packaging contract for per-target recording helpers inside Roast CLI zips (#354).
 *
 * The multi-platform CLI shadow jar may still carry helpers for every OS (library Maven jars
 * remain multi-arch). Each Construo/Roast platform zip must embed a filtered application jar
 * that retains only that target OS's `native/<os>/` tree so Windows dual-arch WGC (~66 MiB
 * uncompressed) is not shipped inside every Linux/macOS zip.
 */
object CliRoastNativePackagingContract {
    const val NATIVE_ROOT: String = "native/"
    const val LINUX_PREFIX: String = "native/linux/"
    const val MACOS_PREFIX: String = "native/macos/"
    const val WINDOWS_PREFIX: String = "native/windows/"

    /** Known OS-level helper roots that must not leak across Roast targets. */
    val OS_NATIVE_PREFIXES: List<String> = listOf(LINUX_PREFIX, MACOS_PREFIX, WINDOWS_PREFIX)

    /**
     * Returns the single `native/<os>/` prefix retained for a Construo target name
     * (`linuxX64`, `macosArm64`, `windowsX64`, …).
     */
    fun keepNativePrefixForRoastTarget(targetName: String): String =
        when {
            targetName.startsWith("linux") -> LINUX_PREFIX
            targetName.startsWith("macos") -> MACOS_PREFIX
            targetName.startsWith("windows") -> WINDOWS_PREFIX
            else ->
                error(
                    "Unsupported Roast target for native filtering: $targetName " +
                        "(expected linux*, macos*, or windows*)"
                )
        }

    /** Rejects malformed keep prefixes that would over-keep or under-keep helper trees. */
    fun requireKnownKeepNativePrefix(keepNativePrefix: String): String {
        check(keepNativePrefix in OS_NATIVE_PREFIXES) {
            "keepNativePrefix must be one of $OS_NATIVE_PREFIXES, got: $keepNativePrefix"
        }
        return keepNativePrefix
    }

    /**
     * Whether a jar entry should be copied into a target-filtered CLI application jar.
     * Non-native entries are always kept (bytecode, agent-runtime resource, services).
     */
    fun shouldIncludeJarEntry(entryName: String, keepNativePrefix: String): Boolean {
        val keep = requireKnownKeepNativePrefix(keepNativePrefix)
        if (!entryName.startsWith(NATIVE_ROOT)) {
            return true
        }
        // Directory markers under native/ that are ancestors of the keep prefix stay so zip
        // tools retain a coherent tree; foreign OS roots are dropped.
        if (entryName.endsWith("/")) {
            return keep.startsWith(entryName) || entryName.startsWith(keep)
        }
        return entryName.startsWith(keep)
    }

    /**
     * Foreign native file entries that must not appear in a Roast-embedded application jar for
     * [keepNativePrefix].
     */
    fun foreignNativeEntries(
        entryNames: Iterable<String>,
        keepNativePrefix: String,
    ): List<String> =
        entryNames
            .filter { name ->
                !name.endsWith("/") &&
                    name.startsWith(NATIVE_ROOT) &&
                    !name.startsWith(keepNativePrefix)
            }
            .sorted()

    /**
     * Validates that [entryNames] from a Roast-embedded CLI jar satisfy the per-target native
     * layout. Returns human-readable errors (empty when valid).
     *
     * Does not require that host helpers are present — a Mac-only CI build may lack Windows
     * prebuilts in the unfiltered jar, and a filtered zip may therefore contain zero natives for
     * a foreign OS build host. The hard rule is **no foreign OS helpers**.
     */
    fun validateEmbeddedJarEntries(
        entryNames: Iterable<String>,
        keepNativePrefix: String,
    ): List<String> {
        val foreign = foreignNativeEntries(entryNames, keepNativePrefix)
        if (foreign.isEmpty()) {
            return emptyList()
        }
        val sample = foreign.take(MAX_FOREIGN_SAMPLES)
        val more =
            if (foreign.size > MAX_FOREIGN_SAMPLES) {
                " (+${foreign.size - MAX_FOREIGN_SAMPLES} more)"
            } else {
                ""
            }
        return listOf(
            "Roast-embedded CLI jar must only retain helpers under $keepNativePrefix; " +
                "found ${foreign.size} foreign native entr${if (foreign.size == 1) "y" else "ies"}: " +
                sample.joinToString() +
                more
        )
    }

    private const val MAX_FOREIGN_SAMPLES: Int = 8
}

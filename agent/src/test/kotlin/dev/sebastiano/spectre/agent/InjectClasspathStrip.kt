package dev.sebastiano.spectre.agent

/**
 * Classpath filtering helpers for inject e2e: strip Spectre `:core` outputs from a child JVM
 * classpath so bootstrap must use the nested inject-runtime jar.
 *
 * Package-visible for unit tests (Windows path shapes must match).
 */
internal object InjectClasspathStrip {
    /**
     * Spectre `:core` module outputs only — never `kotlinx-coroutines-core` or other `*-core`
     * artifacts.
     *
     * Normalizes `\` → `/` on every platform (do not use [java.io.File] path APIs for foreign
     * Windows paths when running tests on Unix — [java.io.File] only treats the host separator as
     * special).
     */
    fun isSpectreCoreClasspathEntry(entry: String): Boolean {
        val n = normalizeClasspathEntry(entry)
        val base = n.substringAfterLast('/')
        // Compiled classes / resources of the :core project (any checkout path).
        if ("/core/build/classes/" in n || "/core/build/resources/" in n) return true
        // Project jar under core/build/libs/core-*.jar (not kotlinx-coroutines-core-*.jar).
        if ("/core/build/libs/" in n && isCoreProjectJarName(base)) return true
        if (base.startsWith("spectre-core-") && base.endsWith(".jar")) return true
        if (base == "spectre-core.jar") return true
        return false
    }

    /** `core-0.1.0-SNAPSHOT.jar` / `core.jar` — not `kotlinx-coroutines-core-*.jar`. */
    fun isCoreProjectJarName(base: String): Boolean =
        base == "core.jar" || (base.startsWith("core-") && base.endsWith(".jar"))

    fun normalizeClasspathEntry(entry: String): String = entry.trim().replace('\\', '/')
}

@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object AgentJarResolution {
    /**
     * Locate a single agent runtime jar on [classPath].
     *
     * Returns `null` when none match. Throws [AmbiguousAgentRuntimeJarException] when more than one
     * candidate is present so callers never silently load a jar based on classpath order.
     */
    fun findRuntimeJarOnClasspath(classPath: String): Path? {
        val matches =
            classPath
                .split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .map(Path::of)
                .filter(::isRuntimeJar)
                .toList()
        return selectSingleRuntimeJar(matches)
    }

    /**
     * Locate a single agent runtime jar under [directory].
     *
     * Returns `null` when none match. Throws [AmbiguousAgentRuntimeJarException] when more than one
     * candidate is present so callers never silently load a jar based on directory order.
     */
    fun findRuntimeJarInDirectory(directory: Path): Path? {
        val matches = Files.list(directory).use { stream -> stream.filter(::isRuntimeJar).toList() }
        return selectSingleRuntimeJar(matches)
    }

    /**
     * True when [directory] is the root of a Spectre monorepo checkout.
     *
     * Requires the agent module layout **and** a Spectre-specific marker (`rootProject.name =
     * "Spectre"` in settings, or `GROUP=dev.sebastiano.spectre` in `gradle.properties`) so
     * unrelated projects that happen to have `agent` / `agent-runtime` subprojects are not treated
     * as Spectre checkouts.
     */
    fun isSpectreSourceCheckout(directory: Path): Boolean {
        if (!Files.isRegularFile(directory.resolve("settings.gradle.kts"))) return false
        if (!Files.isRegularFile(directory.resolve("agent/build.gradle.kts"))) return false
        if (!Files.isRegularFile(directory.resolve("agent-runtime/build.gradle.kts"))) return false
        return hasSpectreIdentityMarker(directory)
    }

    private fun hasSpectreIdentityMarker(directory: Path): Boolean {
        val settings =
            runCatching { Files.readString(directory.resolve("settings.gradle.kts")) }.getOrNull()
        if (settings != null && ACTIVE_SPECTRE_ROOT_NAME.containsMatchIn(settings)) {
            return true
        }
        val propsPath = directory.resolve("gradle.properties")
        if (!Files.isRegularFile(propsPath)) return false
        val props = runCatching { Files.readString(propsPath) }.getOrNull() ?: return false
        return props.lineSequence().any { line ->
            val trimmed = line.trim()
            !trimmed.startsWith("#") && trimmed == "GROUP=dev.sebastiano.spectre"
        }
    }

    /** Active (non-commented) assignment only — comments must not enable the fallback. */
    private val ACTIVE_SPECTRE_ROOT_NAME =
        Regex("""(?m)^\s*rootProject\.name\s*=\s*"Spectre"\s*(//.*)?$""")

    /** Walk parents from [start] and return the nearest Spectre source checkout root, if any. */
    fun findSpectreSourceCheckoutRoot(start: Path): Path? {
        var dir: Path? = start.toAbsolutePath().normalize()
        while (dir != null) {
            if (isSpectreSourceCheckout(dir)) return dir
            dir = dir.parent
        }
        return null
    }

    /**
     * In-repo fallback: only when [cwd] is inside a Spectre source checkout, look under
     * `<checkout>/agent-runtime/build/libs` for a single runtime jar.
     */
    fun findRuntimeJarInRepoFallback(cwd: Path): Path? {
        val root = findSpectreSourceCheckoutRoot(cwd) ?: return null
        val libs = root.resolve("agent-runtime/build/libs")
        if (!Files.isDirectory(libs)) return null
        return findRuntimeJarInDirectory(libs)
    }

    /**
     * Resolve the agent runtime jar using the public attach lookup order.
     *
     * 1. [agentJarPath] if present and a regular file
     * 2. [runtimeJarSystemProperty] path if present and a regular file
     * 3. Classpath auto-discovery
     * 4. In-repo fallback gated on Spectre source-checkout detection from [cwd]
     */
    fun resolveRuntimeJar(
        agentJarPath: Path?,
        runtimeJarSystemProperty: String?,
        classPath: String,
        cwd: Path,
    ): Path {
        val tried = mutableListOf<Path>()

        agentJarPath?.let { path ->
            tried.add(path)
            if (Files.isRegularFile(path)) return path
        }

        val sysProp = runtimeJarSystemProperty?.takeIf { it.isNotBlank() }
        if (sysProp != null) {
            val path = Path.of(sysProp)
            tried.add(path)
            if (Files.isRegularFile(path)) return path
        }

        val classpathRuntime = findRuntimeJarOnClasspath(classPath)
        if (classpathRuntime != null) return classpathRuntime

        val repoFallback = findRuntimeJarInRepoFallback(cwd)
        if (repoFallback != null) return repoFallback

        val checkoutRoot = findSpectreSourceCheckoutRoot(cwd)
        if (checkoutRoot != null) {
            tried.add(checkoutRoot.resolve("agent-runtime/build/libs/agent-runtime-*.jar"))
        }

        throw AgentJarNotFoundException(tried)
    }

    private fun selectSingleRuntimeJar(matches: List<Path>): Path? {
        // Classpaths sometimes list the same physical jar twice (duplicate entries or
        // symlink + real path). Collapse by filesystem identity so only distinct files
        // count as ambiguity. Preserve the original path string: lexical normalize() can
        // break symlink + ".." classpath entries that still resolve on disk.
        val distinct = mutableListOf<Path>()
        for (candidate in matches) {
            val alreadySeen = distinct.any { existing ->
                try {
                    Files.isSameFile(existing, candidate)
                } catch (_: IOException) {
                    existing.toAbsolutePath().normalize() == candidate.toAbsolutePath().normalize()
                }
            }
            if (!alreadySeen) distinct.add(candidate)
        }
        val ordered = distinct.sortedBy { it.toString() }
        return when (ordered.size) {
            0 -> null
            1 -> ordered.single()
            else -> throw AmbiguousAgentRuntimeJarException(ordered)
        }
    }

    private fun isRuntimeJar(path: Path): Boolean =
        Files.isRegularFile(path) && isRuntimeJarName(path.fileName?.toString().orEmpty())

    private fun isRuntimeJarName(name: String): Boolean =
        name.endsWith(".jar") &&
            !name.endsWith("-sources.jar") &&
            !name.endsWith("-javadoc.jar") &&
            (name.startsWith("spectre-agent-runtime-") || name.startsWith("agent-runtime-"))
}

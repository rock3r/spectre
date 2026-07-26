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

package dev.sebastiano.spectre.agent

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal object AgentJarResolution {
    fun findRuntimeJarOnClasspath(classPath: String): Path? =
        classPath
            .split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .map(Path::of)
            .firstOrNull(::isRuntimeJar)

    fun findRuntimeJarInDirectory(directory: Path): Path? =
        Files.list(directory).use { stream ->
            stream
                .filter(::isRuntimeJar)
                .sorted(compareBy { it.fileName.toString() })
                .findFirst()
                .orElse(null)
        }

    private fun isRuntimeJar(path: Path): Boolean =
        Files.isRegularFile(path) && isRuntimeJarName(path.fileName?.toString().orEmpty())

    private fun isRuntimeJarName(name: String): Boolean =
        name.endsWith(".jar") &&
            !name.endsWith("-sources.jar") &&
            !name.endsWith("-javadoc.jar") &&
            (name.startsWith("spectre-agent-runtime-") || name.startsWith("agent-runtime-"))
}

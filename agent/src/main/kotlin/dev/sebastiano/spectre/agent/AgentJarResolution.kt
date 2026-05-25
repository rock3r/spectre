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
            .firstOrNull { path ->
                val name = path.fileName?.toString().orEmpty()
                Files.isRegularFile(path) &&
                    name.endsWith(".jar") &&
                    (name.startsWith("spectre-agent-runtime-") || name.startsWith("agent-runtime-"))
            }
}

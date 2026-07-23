package dev.sebastiano.spectre.agent.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Resolves the nested inject-runtime jar packaged inside `spectre-agent-runtime` at
 * [INJECT_RUNTIME_RESOURCE].
 *
 * Extraction is to a temp file because [SpectreInjectClassLoader] needs a filesystem URL; nested
 * jar URLs are awkward and brittle under the Attach-loaded agent classloader.
 */
internal object InjectRuntimeLocator {
    const val INJECT_RUNTIME_RESOURCE: String = "META-INF/spectre/inject-runtime.jar"

    /**
     * Extracts the inject jar resource from [loader] (typically the agent runtime's loader) to a
     * temp file. Returns `null` when the resource is missing (older agent runtimes / thin-only
     * builds).
     */
    fun extractInjectJar(
        loader: ClassLoader = InjectRuntimeLocator::class.java.classLoader
    ): Path? {
        val stream = loader.getResourceAsStream(INJECT_RUNTIME_RESOURCE) ?: return null
        return stream.use { input ->
            val target =
                Files.createTempFile("spectre-inject-runtime-", ".jar").also { path ->
                    path.toFile().deleteOnExit()
                }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            target
        }
    }
}

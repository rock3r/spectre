package dev.sebastiano.spectre.build

import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/**
 * Layout-only contract for a Roast CLI zip: the embedded application jar must retain only that
 * target's OS native helpers and still embed `spectre/agent-runtime.jar`. Does not extract or
 * launch the bundle, so non-host targets can be verified on any builder OS.
 */
abstract class VerifyRoastCliNativeLayout : DefaultTask() {
    @get:InputFile abstract val artifact: RegularFileProperty

    /** Construo target name (`linuxX64`, `macosArm64`, …) used to derive the keep prefix. */
    @get:Input abstract val roastTargetName: Property<String>

    @TaskAction
    fun verify() {
        val archive = artifact.get().asFile
        val keepPrefix =
            CliRoastNativePackagingContract.keepNativePrefixForRoastTarget(roastTargetName.get())
        ZipFile(archive).use { zip ->
            val applicationJar = resolveApplicationJarEntry(zip)
            val entryNames =
                zip.getInputStream(zip.getEntry(applicationJar)).use { input ->
                    JarInputStream(input).use { jar ->
                        buildList {
                            var entry = jar.nextJarEntry
                            while (entry != null) {
                                add(entry.name)
                                jar.closeEntry()
                                entry = jar.nextJarEntry
                            }
                        }
                    }
                }
            val errors =
                CliRoastNativePackagingContract.validateEmbeddedJarEntries(entryNames, keepPrefix)
            check(errors.isEmpty()) {
                "${archive.name} embedded jar $applicationJar fails per-target native layout:\n" +
                    errors.joinToString("\n") { "  - $it" }
            }
            check(AGENT_RUNTIME_ENTRY in entryNames) {
                "${archive.name} embedded jar $applicationJar is missing $AGENT_RUNTIME_ENTRY"
            }
        }
    }

    companion object {
        const val AGENT_RUNTIME_ENTRY: String = "spectre/agent-runtime.jar"

        /**
         * Prefer a single `*-all.jar` application payload; fail if zero or multiple candidates
         * remain after excluding the jlink runtime and Roast config directories.
         */
        fun resolveApplicationJarEntry(zip: ZipFile): String {
            val candidates =
                zip.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { name ->
                        name.endsWith(".jar") &&
                            !name.contains("/runtime/") &&
                            !name.contains("/app/") &&
                            !name.endsWith("jrt-fs.jar")
                    }
                    .filter { name -> name.endsWith("-all.jar") || name.contains("/cli-") }
                    .sorted()
                    .toList()
            check(candidates.isNotEmpty()) {
                "${zip.name ?: "Roast zip"} does not contain an application *-all.jar"
            }
            check(candidates.size == 1) {
                "${zip.name ?: "Roast zip"} has multiple application jar candidates: $candidates"
            }
            return candidates.single()
        }
    }
}

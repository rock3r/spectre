package dev.sebastiano.spectre.build

import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** Verifies the CLI distribution ZIP carries the jlink runtime it requires to run. */
abstract class VerifyCliDistributionZip : DefaultTask() {
    @get:InputFile abstract val artifact: RegularFileProperty

    @TaskAction
    fun verify() {
        val archive = artifact.get().asFile
        ZipFile(archive).use { zip ->
            check(zip.entries().asSequence().any { it.name.endsWith("/runtime/${javaPath()}") }) {
                "${archive.name} does not contain its jlink runtime executable"
            }
        }
    }

    private fun javaPath(): String = if (isWindows()) "bin/java.exe" else "bin/java"

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
}

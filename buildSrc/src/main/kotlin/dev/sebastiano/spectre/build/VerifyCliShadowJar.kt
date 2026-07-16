package dev.sebastiano.spectre.build

import java.io.File
import java.util.jar.JarFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** Verifies the shrink-only CLI JAR remains directly executable and retains dynamic resources. */
abstract class VerifyCliShadowJar : DefaultTask() {
    @get:InputFile abstract val artifact: RegularFileProperty

    @TaskAction
    fun verify() {
        val jar = artifact.get().asFile
        JarFile(jar).use { archive ->
            check(archive.manifest.mainAttributes.getValue("Main-Class") == CLI_MAIN_CLASS) {
                "${jar.name} does not declare $CLI_MAIN_CLASS as its Main-Class"
            }
            check(archive.getEntry(AGENT_RUNTIME_ENTRY) != null) {
                "${jar.name} does not retain the embedded agent runtime at $AGENT_RUNTIME_ENTRY"
            }
            check(archive.getEntry(KTOR_SERVICE_ENTRY) != null) {
                "${jar.name} does not retain Ktor's ServiceLoader entry at $KTOR_SERVICE_ENTRY"
            }
        }

        val process =
            ProcessBuilder(javaExecutable(), "-jar", jar.absolutePath, "--help")
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "${jar.name} --help failed:\n$output" }
        check("mcp" in output) { "${jar.name} --help did not expose the mcp command:\n$output" }
    }

    private fun javaExecutable(): String =
        File(System.getProperty("java.home"), "bin/java${if (isWindows()) ".exe" else ""}").path

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private companion object {
        private const val CLI_MAIN_CLASS = "dev.sebastiano.spectre.cli.SpectreCliKt"
        private const val AGENT_RUNTIME_ENTRY = "spectre/agent-runtime.jar"
        private const val KTOR_SERVICE_ENTRY = "META-INF/services/io.ktor.server.config.ConfigLoader"
    }
}

package dev.sebastiano.spectre.build

import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** Verifies the CLI runtime image includes attach support and can launch the shrunk CLI. */
abstract class VerifyCliRuntimeImage : DefaultTask() {
    @get:InputDirectory abstract val runtimeImage: DirectoryProperty

    @get:InputFile abstract val artifact: RegularFileProperty

    @TaskAction
    fun verify() {
        val java = runtimeImage.file(javaPath()).get().asFile
        check(java.isFile) { "Runtime image does not contain ${java.path}" }

        val modules = runCommand(java, "--list-modules")
        check(modules.lineSequence().any { it.startsWith("jdk.attach@") }) {
            "Runtime image does not include jdk.attach:\n$modules"
        }

        runCommand(java, "-jar", artifact.get().asFile.path, "--help")
    }

    private fun runCommand(executable: File, vararg arguments: String): String {
        val process =
            ProcessBuilder(executable.path, *arguments)
                .redirectErrorStream(true)
                .start()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            error(
                "${executable.name} ${arguments.joinToString(" ")} did not finish within " +
                    "$COMMAND_TIMEOUT_SECONDS seconds"
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) {
            "${executable.name} ${arguments.joinToString(" ")} failed:\n$output"
        }
        return output
    }

    private fun javaPath(): String = if (isWindows()) "bin/java.exe" else "bin/java"

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private companion object {
        private const val COMMAND_TIMEOUT_SECONDS: Long = 30
        private const val PROCESS_KILL_TIMEOUT_SECONDS: Long = 1
    }
}

package dev.sebastiano.spectre.build

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
            val runtimeEntry =
                zip.entries().asSequence().firstOrNull { it.name.endsWith("/runtime/${javaPath()}") }
                    ?: error("${archive.name} does not contain its jlink runtime executable")
            val distributionRoot = runtimeEntry.name.substringBefore("/runtime/")
            check(zip.getEntry("$distributionRoot/bin/${launcherName()}") != null) {
                "${archive.name} does not contain its launcher in $distributionRoot"
            }
            check(zip.getEntry("$distributionRoot/runtime/spectre-runtime.properties") != null) {
                "${archive.name} does not declare its bundled runtime platform"
            }
            extract(zip)
            verifyLauncher(File(temporaryDir, distributionRoot))
        }
    }

    private fun extract(zip: ZipFile) {
        val root = temporaryDir.toPath()
        zip.entries().asSequence().forEach { entry ->
            val destination = root.resolve(entry.name).normalize()
            check(destination.startsWith(root)) { "ZIP entry escapes the verification directory: ${entry.name}" }
            if (entry.isDirectory) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                zip.getInputStream(entry).use { input -> Files.newOutputStream(destination).use(input::copyTo) }
            }
        }
    }

    private fun verifyLauncher(distributionRoot: File) {
        val launcher = File(distributionRoot, "bin/${launcherName()}")
        val runtimeJava = File(distributionRoot, "runtime/${javaPath()}")
        launcher.setExecutable(true)
        runtimeJava.setExecutable(true)
        val command =
            if (isWindows()) {
                listOf("cmd", "/c", launcher.path, "--help")
            } else {
                listOf(launcher.path, "--help")
            }
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        processBuilder.environment()["JAVA_HOME"] = File(temporaryDir, "missing-java-home").path
        val process = processBuilder.start()
        check(process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "${launcher.name} did not finish within $COMMAND_TIMEOUT_SECONDS seconds"
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) {
            "${launcher.name} did not run with its bundled runtime:\n$output"
        }
    }

    private fun javaPath(): String = if (isWindows()) "bin/java.exe" else "bin/java"

    private fun launcherName(): String = if (isWindows()) "spectre.bat" else "spectre"

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private companion object {
        private const val COMMAND_TIMEOUT_SECONDS: Long = 30
    }
}

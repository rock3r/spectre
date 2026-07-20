package dev.sebastiano.spectre.build

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.Properties
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
            val runtimePlatform =
                zip
                    .getInputStream(zip.getEntry("$distributionRoot/runtime/spectre-runtime.properties"))
                    .use { input ->
                        Properties().apply { load(input) }.let { properties ->
                            "${archiveOperatingSystem(properties.getProperty("spectre.runtime.os"))}-" +
                                properties.getProperty("spectre.runtime.arch")
                        }
                    }
            check(archive.name.endsWith("-$runtimePlatform.zip")) {
                "${archive.name} must name the platform of its bundled runtime ($runtimePlatform)"
            }
            validateEntries(zip)
            extract(archive, zip)
            verifyLauncher(File(temporaryDir, distributionRoot))
        }
    }

    private fun validateEntries(zip: ZipFile) {
        val root = temporaryDir.toPath()
        zip.entries().asSequence().forEach { entry ->
            val destination = root.resolve(entry.name).normalize()
            check(destination.startsWith(root)) { "ZIP entry escapes the verification directory: ${entry.name}" }
        }
    }

    private fun extract(archive: File, zip: ZipFile) {
        if (isWindows()) {
            extractWindows(zip)
        } else {
            extractUnix(archive)
        }
    }

    private fun extractUnix(archive: File) {
        val process = ProcessBuilder("unzip", "-o", "-q", archive.path, "-d", temporaryDir.path).start()
        check(process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "unzip did not finish within $COMMAND_TIMEOUT_SECONDS seconds"
        }
        check(process.exitValue() == 0) { "unzip failed: ${process.errorStream.bufferedReader().readText()}" }
    }

    private fun extractWindows(zip: ZipFile) {
        val root = temporaryDir.toPath()
        zip.entries().asSequence().forEach { entry ->
            val destination = root.resolve(entry.name).normalize()
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
        val command =
            if (isWindows()) {
                listOf("cmd", "/c", launcher.path, "--help")
            } else {
                listOf(launcher.path, "--help")
            }
        val processBuilder = ProcessBuilder(command).redirectErrorStream(true)
        // Point JAVA_HOME at a non-existent path so the launcher must use the ZIP's jlink
        // runtime (or fail). The Windows bat probes APP_HOME\\runtime before honouring this.
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

    private fun archiveOperatingSystem(value: String): String =
        when (value) {
            "Mac OS X" -> "macos"
            "Windows" -> "windows"
            "Linux" -> "linux"
            else -> error("Unsupported bundled runtime operating system: $value")
        }

    private companion object {
        private const val COMMAND_TIMEOUT_SECONDS: Long = 30
    }
}

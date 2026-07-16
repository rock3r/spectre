package dev.sebastiano.spectre.build

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/** Verifies a host-platform Roast bundle carries and starts the JVM it packages. */
abstract class VerifyRoastCliDistribution : DefaultTask() {
    @get:InputFile abstract val artifact: RegularFileProperty

    @get:Input abstract val launcherPath: Property<String>

    @get:Input abstract val runtimeJavaPath: Property<String>

    @TaskAction
    fun verify() {
        val archive = artifact.get().asFile
        val launcher = launcherPath.get()
        ZipFile(archive).use { zip ->
            check(zip.getEntry(launcher) != null) { "${archive.name} does not contain $launcher" }
            val applicationRoot = launcher.substringBeforeLast('/')
            check(zip.getEntry("$applicationRoot/runtime/release") != null) {
                "${archive.name} does not contain a bundled JVM release file"
            }
            check(zip.getEntry(runtimeJavaPath.get()) != null) {
                "${archive.name} does not contain the JVM launcher required to start the daemon"
            }
            check(zip.getEntry("$applicationRoot/app/spectre.json") != null) {
                "${archive.name} does not contain Roast's application configuration"
            }
            validateEntries(zip)
            extract(archive, zip)
        }
        verifyLauncher(File(temporaryDir, launcher))
        verifyRuntimeJava(File(temporaryDir, runtimeJavaPath.get()))
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
        val result =
            ProcessBuilder("unzip", "-o", "-q", archive.absolutePath, "-d", temporaryDir.absolutePath)
                .redirectErrorStream(true)
                .start()
        check(result.waitFor(EXTRACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            result.destroyForcibly()
            "Timed out extracting ${archive.name}"
        }
        check(result.exitValue() == 0) { "Could not extract ${archive.name}" }
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

    private fun verifyLauncher(launcher: File) {
        val process =
            ProcessBuilder(launcher.absolutePath, "--help")
                .directory(launcher.parentFile)
                .redirectErrorStream(true)
                .start()
        check(process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out running bundled launcher ${launcher.name}"
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "Bundled launcher failed: $output" }
        check("Usage: spectre" in output) { "Bundled launcher did not run the Spectre CLI: $output" }
    }

    private fun verifyRuntimeJava(java: File) {
        // ZIP extraction does not reliably retain the executable bit on every host. The daemon
        // launcher restores it before spawning this bundled JVM; mirror that contract here.
        java.setExecutable(true, false)
        val process = ProcessBuilder(java.absolutePath, "-version").redirectErrorStream(true).start()
        check(process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out running bundled JVM launcher ${java.name}"
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "Bundled JVM launcher failed: $output" }
    }

    private companion object {
        const val EXTRACTION_TIMEOUT_SECONDS = 30L
        const val LAUNCH_TIMEOUT_SECONDS = 20L
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
}

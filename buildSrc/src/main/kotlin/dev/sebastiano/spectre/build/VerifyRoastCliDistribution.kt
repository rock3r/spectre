package dev.sebastiano.spectre.build

import java.io.File
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
            check(zip.getEntry("$applicationRoot/app/spectre.json") != null) {
                "${archive.name} does not contain Roast's application configuration"
            }
        }
        extract(archive)
        verifyLauncher(File(temporaryDir, launcher))
    }

    private fun extract(archive: File) {
        val result =
            ProcessBuilder("unzip", "-o", "-q", archive.absolutePath, "-d", temporaryDir.absolutePath)
                .redirectErrorStream(true)
                .start()
        check(result.waitFor(EXTRACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out extracting ${archive.name}"
        }
        check(result.exitValue() == 0) { "Could not extract ${archive.name}" }
    }

    private fun verifyLauncher(launcher: File) {
        val process =
            ProcessBuilder(launcher.absolutePath, "--help")
                .directory(launcher.parentFile)
                .redirectErrorStream(true)
                .start()
        check(process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out running bundled launcher ${launcher.name}"
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "Bundled launcher failed: $output" }
        check("Usage: spectre" in output) { "Bundled launcher did not run the Spectre CLI: $output" }
    }

    private companion object {
        const val EXTRACTION_TIMEOUT_SECONDS = 30L
        const val LAUNCH_TIMEOUT_SECONDS = 20L
    }
}

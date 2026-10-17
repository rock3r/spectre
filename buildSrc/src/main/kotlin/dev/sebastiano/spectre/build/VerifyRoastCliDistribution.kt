package dev.sebastiano.spectre.build

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarInputStream
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

    /**
     * OS native prefix the embedded CLI application jar must retain exclusively (e.g.
     * `native/macos/`). See [CliRoastNativePackagingContract].
     */
    @get:Input abstract val keepNativePrefix: Property<String>

    @TaskAction
    fun verify() {
        val archive = artifact.get().asFile
        val launcher = launcherPath.get()
        lateinit var applicationJarEntry: String
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
            applicationJarEntry = VerifyRoastCliNativeLayout.resolveApplicationJarEntry(zip)
            validateNativeLayout(zip, applicationRoot)
            validateEntries(zip)
            extract(archive, zip)
        }
        verifyLauncher(
            File(temporaryDir, launcher),
            File(temporaryDir, runtimeJavaPath.get()),
            File(temporaryDir, applicationJarEntry),
        )
        verifyRuntimeJava(File(temporaryDir, runtimeJavaPath.get()))
    }

    private fun validateNativeLayout(zip: ZipFile, applicationRoot: String) {
        val keepPrefix = keepNativePrefix.get()
        val applicationJar = VerifyRoastCliNativeLayout.resolveApplicationJarEntry(zip)
        check(applicationJar.startsWith("$applicationRoot/") || !applicationRoot.contains('/')) {
            "${zip.name ?: "Roast zip"} application jar $applicationJar is outside launcher root $applicationRoot"
        }
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
            "${zip.name ?: "Roast zip"} embedded jar $applicationJar fails per-target native layout:\n" +
                errors.joinToString("\n") { "  - $it" }
        }
        check(VerifyRoastCliNativeLayout.AGENT_RUNTIME_ENTRY in entryNames) {
            "${zip.name ?: "Roast zip"} embedded jar $applicationJar is missing " +
                VerifyRoastCliNativeLayout.AGENT_RUNTIME_ENTRY
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

    private fun verifyLauncher(launcher: File, runtimeJava: File, applicationJar: File) {
        // Point the bootstrap at an empty cache directory: the first launch must train
        // and promote a Leyden AOT cache there (this is what proves the packaged
        // bootstrap actually execs its child JVM — including restoring the bundled
        // java launcher's execute bit after ZIP extraction — instead of silently
        // degrading to its in-process fallback), and the second must consume it.
        val aotCacheDir = File(temporaryDir, "verify-aot-cache")
        Files.createDirectories(aotCacheDir.toPath())
        // The task temp dir survives re-runs; start empty so the first launch below
        // really is a first (training) launch.
        aotCacheDir.listFiles().orEmpty().forEach { it.delete() }
        val firstStderr = File(temporaryDir, "launcher-first-stderr.txt")
        val firstOutput = runLauncher(launcher, aotCacheDir, firstStderr)
        check("Usage: spectre" in firstOutput.withoutAnsiSequences()) {
            "Bundled launcher did not run the Spectre CLI: $firstOutput"
        }
        // The first launch trains before it runs the real command, and training must
        // not leak onto either of the real command's streams: stdout is a protocol
        // surface (spectre mcp, --json), and Leyden diagnostics default to stdout
        // (record runs also append an "AOTConfiguration recorded" line there). The
        // bootstrap therefore trains with discarded stdio and silences Leyden
        // logging on production launches.
        check(firstOutput.withoutAnsiSequences().lineSequence().none { line -> AOT_NOISE_MARKERS.any { it in line } }) {
            "Bundled launcher leaked AOT training noise to stdout: $firstOutput"
        }
        val firstStderrText = firstStderr.readText()
        check("[aot]" !in firstStderrText && "AOTCache" !in firstStderrText) {
            "Bundled launcher leaked AOT training noise to stderr: $firstStderrText"
        }
        val caches = aotCacheDir.listFiles { file -> file.name.endsWith(".aot") }.orEmpty()
        check(caches.size == 1) {
            "Bundled launcher did not train an AOT cache in $aotCacheDir: " +
                aotCacheDir.listFiles().orEmpty().map { it.name }
        }
        verifyCacheIsConsumed(runtimeJava, applicationJar, caches.single(), launcher.parentFile)
        val secondStderr = File(temporaryDir, "launcher-second-stderr.txt")
        val secondOutput = runLauncher(launcher, aotCacheDir, secondStderr)
        check("Usage: spectre" in secondOutput.withoutAnsiSequences()) {
            "Bundled launcher did not run the Spectre CLI with a trained AOT cache: $secondOutput"
        }
        check(secondOutput.withoutAnsiSequences().lineSequence().none { line -> AOT_NOISE_MARKERS.any { it in line } }) {
            "Bundled launcher leaked AOT noise to stdout with a trained AOT cache: $secondOutput"
        }
        // A warm launch must be quiet on stderr: no rejected cache, and no JNA
        // restricted-method warning (the child JVM gets --enable-native-access).
        val secondStderrText = secondStderr.readText()
        check("[aot]" !in secondStderrText && "restricted method" !in secondStderrText) {
            "Bundled launcher was not quiet with a trained AOT cache: $secondStderrText"
        }
    }

    /**
     * Proves the trained cache is actually consumable, not merely present: run the
     * bundled runtime against it with `-XX:AOTMode=on`, which makes the JVM exit
     * non-zero if the cache is unusable, in the production launch's option shape.
     * This is the assertion that catches training/production option drift (JEP 483
     * requires identical module options — e.g. `--enable-native-access` — across
     * record, assembly, and production, or the JVM silently discards the cache).
     */
    private fun verifyCacheIsConsumed(java: File, applicationJar: File, cache: File, workingDir: File) {
        java.setExecutable(true, false)
        val stderrFile = File(temporaryDir, "cache-consumption-stderr.txt")
        val process =
            ProcessBuilder(
                    java.absolutePath,
                    "-XX:AOTCache=${cache.absolutePath}",
                    "-XX:AOTMode=on",
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    applicationJar.absolutePath,
                    "dev.sebastiano.spectre.cli.SpectreCliKt",
                    "--help",
                )
                .directory(workingDir)
                .redirectError(stderrFile)
                .start()
        check(process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out consuming the trained AOT cache ${cache.name}"
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0 && "Usage: spectre" in output.withoutAnsiSequences()) {
            "Bundled JVM rejected the cache trained by the launcher: ${stderrFile.readText()}"
        }
    }

    private fun runLauncher(launcher: File, aotCacheDir: File, stderrFile: File): String {
        val process =
            ProcessBuilder(launcher.absolutePath, "--help")
                .directory(launcher.parentFile)
                .redirectError(stderrFile)
                .apply { environment()["SPECTRE_AOT_CACHE_DIR"] = aotCacheDir.absolutePath }
                .start()
        check(process.waitFor(LAUNCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out running bundled launcher ${launcher.name}"
        }
        val output = process.inputStream.bufferedReader().readText()
        check(process.exitValue() == 0) { "Bundled launcher failed: $output" }
        return output
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
        val ANSI_ESCAPE_SEQUENCE = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")

        /** Leyden/JVM lines that must never reach the CLI's stdout (a protocol surface). */
        val AOT_NOISE_MARKERS = listOf("[aot]", "AOTCache", "AOTConfiguration")
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private fun String.withoutAnsiSequences(): String = replace(ANSI_ESCAPE_SEQUENCE, "")
}

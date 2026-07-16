package dev.sebastiano.spectre.build

import java.io.File
import java.util.jar.JarFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
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

        verifyHelp(jar)
        verifyMcpToolDiscovery(jar)
    }

    private fun verifyHelp(jar: File) {
        val process = ProcessBuilder(javaExecutable(), "-jar", jar.absolutePath, "--help").redirectErrorStream(true).start()
        try {
            val output = readUntil(process) { "mcp" in it }
            check(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "${jar.name} --help failed:\n$output"
            }
            check("mcp" in output) { "${jar.name} --help did not expose the mcp command:\n$output" }
        } finally {
            process.destroyForcibly()
        }
    }

    private fun verifyMcpToolDiscovery(jar: File) {
        val process = ProcessBuilder(javaExecutable(), "-jar", jar.absolutePath, "mcp").redirectErrorStream(true).start()
        try {
            val writer = process.outputStream.bufferedWriter()
            writer.appendLine(INITIALIZE_REQUEST)
            writer.appendLine(INITIALIZED_NOTIFICATION)
            writer.appendLine(TOOLS_LIST_REQUEST)
            writer.flush()
            val output = readUntil(process) { "\"id\":2" in it && "\"type_text\"" in it }
            check("\"id\":2" in output && "\"type_text\"" in output) {
                "${jar.name} mcp did not return its tool list:\n$output"
            }
            process.outputStream.close()
            check(process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "${jar.name} mcp did not stop after its input closed:\n$output"
            }
        } finally {
            process.destroyForcibly()
        }
    }

    private fun readUntil(process: Process, complete: (String) -> Boolean): String =
        Executors.newSingleThreadExecutor().let { executor ->
            val output = executor.submit<String> {
                buildString {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            appendLine(line)
                            if (complete(toString())) return@useLines
                        }
                    }
                }
            }
            try {
                output.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (exception: TimeoutException) {
                process.destroyForcibly()
                output.get(PROCESS_KILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                error("Timed out after $PROCESS_TIMEOUT_SECONDS seconds while reading ${process.info().command().orElse("the CLI")}")
            } finally {
                if (!output.isDone) output.cancel(true)
                executor.shutdownNow()
            }
        }

    private fun javaExecutable(): String =
        File(System.getProperty("java.home"), "bin/java${if (isWindows()) ".exe" else ""}").path

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private companion object {
        private const val CLI_MAIN_CLASS = "dev.sebastiano.spectre.cli.SpectreCliKt"
        private const val AGENT_RUNTIME_ENTRY = "spectre/agent-runtime.jar"
        private const val KTOR_SERVICE_ENTRY = "META-INF/services/io.ktor.server.config.ConfigLoader"
        private const val PROCESS_TIMEOUT_SECONDS: Long = 10
        private const val PROCESS_KILL_TIMEOUT_SECONDS: Long = 1
        private const val INITIALIZE_REQUEST =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"spectre-r8-smoke\",\"version\":\"1\"}}}"
        private const val INITIALIZED_NOTIFICATION =
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"
        private const val TOOLS_LIST_REQUEST =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"
    }
}

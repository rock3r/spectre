@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64

internal object WindowsAttachHelper {
    private const val ARGUMENT_COUNT = 3
    private const val RESPONSE_FIELD_COUNT = 4
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == ARGUMENT_COUNT) { "expected pid, agent jar, and agent arguments" }
        try {
            AgentAttach.attachAndLoadDirect(
                args[0].toLong(),
                Path.of(decode(args[1])),
                decode(args[2]),
            )
            println("OK")
        } catch (error: SpectreAttachException) {
            val cause = error.cause
            println(
                listOf(
                        "ERROR",
                        encode(error.message.orEmpty()),
                        encode(cause?.javaClass?.simpleName.orEmpty()),
                        encode(cause?.message.orEmpty()),
                    )
                    .joinToString("\t")
            )
            kotlin.system.exitProcess(2)
        }
    }

    fun command(pid: Long, agentJar: Path, agentArgs: String): List<String> =
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            helperClassPath(),
            WindowsAttachHelper::class.java.name,
            pid.toString(),
            encode(agentJar.toString()),
            encode(agentArgs),
        )

    private fun helperClassPath(): String =
        sequenceOf(WindowsAttachHelper::class.java, kotlin.Unit::class.java)
            .mapNotNull { it.protectionDomain?.codeSource?.location?.toURI()?.let(Path::of) }
            .map(Path::toString)
            .plus(System.getProperty("java.class.path").orEmpty().split(File.pathSeparator))
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(File.pathSeparator)

    fun failure(result: String): SpectreAttachException? {
        val response = result.lineSequence().lastOrNull().orEmpty()
        if (response == "OK") return null
        val fields = response.split('\t')
        if (fields.size != RESPONSE_FIELD_COUNT || fields[0] != "ERROR") {
            return invalidResponse(result)
        }
        return try {
            val causeType = decode(fields[2])
            val causeDetail = decode(fields[3])
            val cause =
                when (causeType) {
                    "AttachNotSupportedException" ->
                        RemoteAttachFailure(RemoteAttachFailureKind.AttachNotSupported, causeDetail)
                    "AgentLoadException" ->
                        RemoteAttachFailure(RemoteAttachFailureKind.AgentLoad, causeDetail)
                    "" -> null
                    else -> RuntimeException("$causeType: $causeDetail")
                }
            SpectreAttachExceptionImpl(decode(fields[1]), cause)
        } catch (_: IllegalArgumentException) {
            invalidResponse(result)
        }
    }

    private fun invalidResponse(result: String) =
        SpectreAttachExceptionImpl("Invalid Windows attach helper response: $result", null)

    private fun encode(value: String) =
        encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String) = String(decoder.decode(value), StandardCharsets.UTF_8)
}

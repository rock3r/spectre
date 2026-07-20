package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.MAX_FRAME_BYTES

/**
 * Invokes `ComposeAutomator.capture` reflectively and maps the result onto the agent wire
 * [AgentResponse.Capture] envelope.
 */
internal object AtomicCaptureReflectiveMapper {

    fun invoke(automator: Any, windowIndex: Int): AgentResponse {
        // `ComposeAutomator.capture(windowIndex: Int = 0)` is a single Kotlin method with a
        // default param. Without `@JvmOverloads` the JVM signature is `capture(int)`.
        val captureMethod =
            automator.javaClass.methods.firstOrNull {
                it.name == "capture" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
                ?: return AgentResponse.Error(
                    "ComposeAutomator does not expose capture(windowIndex: Int) on this build"
                )
        val result =
            captureMethod.invoke(automator, windowIndex)
                ?: return AgentResponse.Error("capture returned null")
        return map(result, windowIndex)
    }

    private fun map(result: Any, windowIndex: Int): AgentResponse {
        val resultClass = result.javaClass
        val document =
            resultClass.getMethod("getDocument").invoke(result)
                ?: return AgentResponse.Error("AtomicCapture.document was null")
        val documentClass = document.javaClass
        val summary =
            documentClass.getMethod("getSummary").invoke(document)
                ?: return AgentResponse.Error("CaptureDocument.summary was null")
        val summaryClass = summary.javaClass
        val pngBytes = resultClass.getMethod("getPngBytes").invoke(result) as ByteArray
        val captureJson = resultClass.getMethod("getCaptureJson").invoke(result) as String
        val captureJsonUtf8 = captureJson.toByteArray(Charsets.UTF_8)
        // CBOR envelope is larger than raw bytes; leave headroom so Framing.writeFrame does not
        // kill the connection after a successful capture.
        val rawBytes = pngBytes.size.toLong() + captureJsonUtf8.size.toLong()
        val maxRawPayload = (MAX_FRAME_BYTES * 3L) / 4L
        if (rawBytes > maxRawPayload) {
            return AgentResponse.Error(
                "Atomic capture is too large for the agent IPC frame limit " +
                    "(png+json=${rawBytes}B, max≈${maxRawPayload}B). Capture a smaller window " +
                    "or reduce UI density; a path-based transfer is tracked with payload limits."
            )
        }
        return AgentResponse.Capture(
            windowIndex = windowIndex,
            schemaVersion = documentClass.getMethod("getSchemaVersion").invoke(document) as Int,
            captureJsonUtf8 = captureJsonUtf8,
            pngBytes = pngBytes,
            nodeCount = summaryClass.getMethod("getNodeCount").invoke(summary) as Int,
            taggedNodeCount = summaryClass.getMethod("getTaggedNodeCount").invoke(summary) as Int,
            textedNodeCount = summaryClass.getMethod("getTextedNodeCount").invoke(summary) as Int,
            imageWidth = summaryClass.getMethod("getImageWidth").invoke(summary) as Int,
            imageHeight = summaryClass.getMethod("getImageHeight").invoke(summary) as Int,
            captureDurationMs =
                summaryClass.getMethod("getCaptureDurationMs").invoke(summary) as Long,
        )
    }
}

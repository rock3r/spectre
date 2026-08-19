package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.FrameLimits

/**
 * Invokes `ComposeAutomator.capture` reflectively and maps the result onto the agent wire
 * [AgentResponse.Capture] envelope.
 */
internal object AtomicCaptureReflectiveMapper {

    /** Field names, ints, and discriminators around the two byte strings — a few hundred bytes. */
    private const val CAPTURE_ENVELOPE_HEADROOM_BYTES: Long = 64L * 1024L

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
                    message =
                        "ComposeAutomator does not expose capture(windowIndex: Int) on this build",
                    category =
                        dev.sebastiano.spectre.agent.transport.AgentErrorCategory
                            .UnsupportedOperation
                            .wireName,
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
        // Both bulk fields are @ByteString, so the CBOR envelope around them is a small constant
        // rather than a multiple of their size — reserve that constant instead of a percentage, or
        // raising the budget would not make the extra budget usable.
        val rawBytes = pngBytes.size.toLong() + captureJsonUtf8.size.toLong()
        val maxRawPayload = FrameLimits.maxFrameBytes.toLong() - CAPTURE_ENVELOPE_HEADROOM_BYTES
        if (rawBytes > maxRawPayload) {
            return AgentResponse.Error(
                message =
                    "Atomic capture is too large for the agent IPC frame limit " +
                        "(png+json=${rawBytes}B, max≈${maxRawPayload}B). Raise the budget with " +
                        "--max-frame-bytes / SPECTRE_MAX_FRAME_BYTES, or capture a smaller window.",
                category =
                    dev.sebastiano.spectre.agent.transport.AgentErrorCategory.PayloadTooLarge
                        .wireName,
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

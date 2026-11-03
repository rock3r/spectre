package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.FrameLimits
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

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

    /**
     * Invokes `ComposeAutomator.captureWithWindowStill(windowIndex, surfaceId, BufferedImage,
     * Rectangle)` with the window still the daemon took (#559). A target whose `spectre-core`
     * predates that method gets an unsupported-operation error rather than a fallback: the
     * in-process path would need `spectre-recording` in the target and could never substitute a
     * desktop crop anyway.
     */
    fun invokeWithWindowStill(
        automator: Any,
        request: AgentRequest.CaptureWithWindowStill,
    ): AgentResponse {
        val method =
            automator.javaClass.methods.firstOrNull {
                it.name == "captureWithWindowStill" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType,
                            String::class.java,
                            BufferedImage::class.java,
                            Rectangle::class.java,
                        )
                    )
            }
                ?: return AgentResponse.Error(
                    message =
                        "The spectre-core in the target application is too old to accept a " +
                            "window still from the Spectre daemon. Update the target's " +
                            "spectre-core dependency to the same version as the Spectre CLI.",
                    category = AgentErrorCategory.UnsupportedOperation.wireName,
                )
        val image =
            ImageIO.read(ByteArrayInputStream(request.windowStillPng))
                ?: return AgentResponse.Error(
                    message = "The window still sent by the Spectre daemon is not a readable PNG",
                    category = AgentErrorCategory.InternalError.wireName,
                )
        val bounds = request.stillWindowBounds
        val result =
            method.invoke(
                automator,
                request.windowIndex,
                request.surfaceId,
                image,
                Rectangle(bounds.x, bounds.y, bounds.width, bounds.height),
            ) ?: return AgentResponse.Error("captureWithWindowStill returned null")
        return map(result, request.windowIndex)
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

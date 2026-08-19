@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.FrameLimits
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The atomic-capture guard decides whether a capture fits the frame budget. Bulk fields now encode
 * as CBOR byte strings, so the budget a user configures should be nearly all usable — a percentage
 * cutoff would silently withhold a quarter of it, and raising the budget would not deliver.
 */
class AtomicCaptureBudgetTest {

    @AfterTest fun restore() = FrameLimits.resetToEnvironment()

    @Test
    fun `a capture just under the budget is served, not refused`() {
        FrameLimits.configure(BUDGET)
        // 800KiB of a 1MiB budget: over the old 75% cutoff, comfortably inside the real envelope.
        val handler = ReflectiveAutomatorHandler(automatorWithPng(800 * 1024))

        val response = handler.handle(AgentRequest.Capture(windowIndex = 0))

        assertIs<AgentResponse.Capture>(response, "capture within budget must not be refused")
    }

    @Test
    fun `a capture over the budget is refused as payloadTooLarge`() {
        FrameLimits.configure(BUDGET)
        val handler = ReflectiveAutomatorHandler(automatorWithPng(BUDGET + 1))

        val response = handler.handle(AgentRequest.Capture(windowIndex = 0))

        val error = assertIs<AgentResponse.Error>(response)
        assertEquals(
            AgentErrorCategory.PayloadTooLarge.wireName,
            error.category,
            "an oversized capture is a payload problem, not an internal error: ${error.message}",
        )
        assertTrue(error.message.contains("too large", ignoreCase = true), error.message)
    }

    private fun automatorWithPng(size: Int): Any =
        FakeCaptureAutomator(ByteArray(size) { 0xAB.toByte() })

    private companion object {
        const val BUDGET: Int = 1024 * 1024
    }
}

/** Minimal stand-in exposing what [ReflectiveAutomatorHandler] looks up for a capture. */
private class FakeCaptureAutomator(
    private val png: ByteArray,
    private val windows: List<Any> = emptyList(),
) {
    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = windows

    @Suppress("unused") fun allNodes(): List<Any> = windows

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = windows

    @Suppress("unused") fun capture(windowIndex: Int): Any = FakeCapture(png)
}

private class FakeCapture(
    private val png: ByteArray,
    private val json: String = """{"schemaVersion":1}""",
    private val document: Any = FakeDocument(),
) {
    @Suppress("unused") fun getDocument(): Any = document

    @Suppress("unused") fun getPngBytes(): ByteArray = png

    @Suppress("unused") fun getCaptureJson(): String = json
}

private class FakeDocument(
    private val schemaVersion: Int = 1,
    private val summary: Any = FakeSummary(),
) {
    @Suppress("unused") fun getSchemaVersion(): Int = schemaVersion

    @Suppress("unused") fun getSummary(): Any = summary
}

@Suppress("LongParameterList")
private class FakeSummary(
    private val nodeCount: Int = 1,
    private val taggedNodeCount: Int = 1,
    private val textedNodeCount: Int = 1,
    private val imageWidth: Int = 10,
    private val imageHeight: Int = 10,
    private val captureDurationMs: Long = 1L,
) {
    @Suppress("unused") fun getNodeCount(): Int = nodeCount

    @Suppress("unused") fun getTaggedNodeCount(): Int = taggedNodeCount

    @Suppress("unused") fun getTextedNodeCount(): Int = textedNodeCount

    @Suppress("unused") fun getImageWidth(): Int = imageWidth

    @Suppress("unused") fun getImageHeight(): Int = imageHeight

    @Suppress("unused") fun getCaptureDurationMs(): Long = captureDurationMs
}

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.IpcClient
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import dev.sebastiano.spectre.agent.transport.logLabel
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live connection to a Spectre agent running inside a target JVM.
 *
 * Obtained from [AgentAttach.attach]. Implements [AutoCloseable]; closing it issues an
 * [AgentRequest.Detach] over the wire (Path A of the plan's D-7) before tearing down the underlying
 * socket. The target JVM's shutdown hook (Path B) covers crash cleanup.
 *
 * Current wire surface: windows, allNodes, findByTestTag, click, typeText, screenshot, capture,
 * plus detach. Streaming/long-poll ops are deferred follow-ups (Q-3).
 *
 * Not thread-safe. Callers needing concurrent automator access must serialise externally.
 *
 * @property pid the target JVM's process id; informational, set by [AgentAttach.attach].
 */
@ExperimentalSpectreAgentApi
public class AttachedAutomator
internal constructor(
    public val pid: Long,
    private val client: IpcClient,
    private val detacher: Runnable,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /** Read the current list of tracked windows in the target. */
    @Throws(IOException::class)
    public fun windows(): List<WindowSummaryDto> {
        val resp = exchange(AgentRequest.Windows)
        return (resp as? AgentResponse.Windows)?.windows ?: throw wireMismatch("Windows", resp)
    }

    /** Read the full semantics tree across all known windows. */
    @Throws(IOException::class)
    public fun allNodes(): List<NodeSnapshotDto> {
        val resp = exchange(AgentRequest.AllNodes)
        return (resp as? AgentResponse.Nodes)?.nodes ?: throw wireMismatch("Nodes", resp)
    }

    /** Find nodes whose `testTag` semantic equals [tag]. */
    @Throws(IOException::class)
    public fun findByTestTag(tag: String): List<NodeSnapshotDto> {
        val resp = exchange(AgentRequest.FindByTestTag(tag))
        return (resp as? AgentResponse.Nodes)?.nodes ?: throw wireMismatch("Nodes", resp)
    }

    /**
     * Synthesise a click on the node identified by [nodeKey] (the canonical
     * `surfaceId:ownerIndex:nodeId` string returned in [NodeSnapshotDto.key]).
     */
    @Throws(IOException::class)
    public fun click(nodeKey: String) {
        val resp = exchange(AgentRequest.Click(nodeKey))
        if (resp !is AgentResponse.Ok) throw wireMismatch("Ok", resp)
    }

    /** Synthesise key events that type [text] into whatever holds focus in the target. */
    @Throws(IOException::class)
    public fun typeText(text: String) {
        val resp = exchange(AgentRequest.TypeText(text))
        if (resp !is AgentResponse.Ok) throw wireMismatch("Ok", resp)
    }

    /** Capture a PNG of the target JVM's screen. Returns the raw PNG bytes. */
    @Throws(IOException::class)
    public fun screenshot(): ByteArray {
        val resp = exchange(AgentRequest.Screenshot)
        return (resp as? AgentResponse.Screenshot)?.pngBytes
            ?: throw wireMismatch("Screenshot", resp)
    }

    /**
     * Atomic capture of one tracked window: semantics tree + window PNG taken back-to-back.
     *
     * Returns the versioned capture JSON, PNG bytes, and summary counters. Prefer writing the
     * artifacts to disk and keeping only the summary in agent context.
     */
    @Throws(IOException::class)
    public fun capture(windowIndex: Int = 0): AtomicCaptureResult {
        val resp = exchange(AgentRequest.Capture(windowIndex))
        val capture = resp as? AgentResponse.Capture ?: throw wireMismatch("Capture", resp)
        return AtomicCaptureResult(
            windowIndex = capture.windowIndex,
            schemaVersion = capture.schemaVersion,
            captureJson = capture.captureJsonUtf8.toString(Charsets.UTF_8),
            pngBytes = capture.pngBytes,
            nodeCount = capture.nodeCount,
            taggedNodeCount = capture.taggedNodeCount,
            textedNodeCount = capture.textedNodeCount,
            imageWidth = capture.imageWidth,
            imageHeight = capture.imageHeight,
            captureDurationMs = capture.captureDurationMs,
        )
    }

    /**
     * Send [AgentRequest.Detach], wait for [AgentResponse.Detached], close the underlying client.
     * Idempotent — calling twice is a no-op.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            client.send(AgentRequest.Detach)
        } catch (_: Exception) {
            // Best-effort: the server may have already closed before sending `Detached`
            // (`IOException`), or the response may be malformed at the framing layer
            // (`IllegalStateException` from `Framing.readFrame`'s length check) or at the
            // CBOR layer (`SerializationException` from `WireCodec.decodeResponse`). None
            // of these should prevent local teardown — the `finally` below closes the
            // client and runs the detacher regardless. Errors (OOM, StackOverflow) are
            // intentionally NOT caught. Bugbot caught the narrow-catch (LOW); Detekt's
            // TooGenericExceptionCaught is suppressed with rationale.
        } finally {
            runCatching { client.close() }
            runCatching { detacher.run() }
        }
    }

    private fun exchange(request: AgentRequest): AgentResponse {
        check(!closed.get()) { "AttachedAutomator is closed" }
        val resp = client.send(request)
        if (resp is AgentResponse.Error) {
            throw IOException("Agent reported error for ${request.logLabel}: ${resp.message}")
        }
        return resp
    }

    private fun wireMismatch(expected: String, actual: AgentResponse): IOException =
        IOException("Wire protocol mismatch: expected $expected, got ${actual::class.simpleName}")
}

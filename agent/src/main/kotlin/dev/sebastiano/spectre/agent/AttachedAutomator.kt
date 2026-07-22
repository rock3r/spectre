package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.IpcClient
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto
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
 * windowIdentity, plus detach. Streaming/long-poll ops are deferred follow-ups (Q-3).
 *
 * Not thread-safe. Callers needing concurrent automator access must serialise externally.
 *
 * @property pid the target JVM's process id; informational, set by [AgentAttach.attach].
 */
@ExperimentalSpectreAgentApi
@Suppress("TooManyFunctions") // Public wire surface mirrors ComposeAutomator selectors + waits.
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

    /** Find nodes by text (#202). See in-process `findByText`. */
    @Throws(IOException::class)
    public fun findByText(text: String, exact: Boolean = true): List<NodeSnapshotDto> {
        val resp = exchange(AgentRequest.FindByText(text = text, exact = exact))
        return (resp as? AgentResponse.Nodes)?.nodes ?: throw wireMismatch("Nodes", resp)
    }

    /** Find nodes by content description (#202). */
    @Throws(IOException::class)
    public fun findByContentDescription(description: String): List<NodeSnapshotDto> {
        val resp = exchange(AgentRequest.FindByContentDescription(description))
        return (resp as? AgentResponse.Nodes)?.nodes ?: throw wireMismatch("Nodes", resp)
    }

    /**
     * Find nodes by Compose [role] name (e.g. `"Button"`) (#202). Unknown names throw
     * [SpectreAgentException] with category `invalidSelector`.
     */
    @Throws(IOException::class)
    public fun findByRole(role: String): List<NodeSnapshotDto> {
        val resp = exchange(AgentRequest.FindByRole(role))
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

    /**
     * Capture a PNG of a tracked window (default) or the full desktop when [fullscreen] is true.
     *
     * Without [windowIndex], [surfaceId], or [fullscreen], targets window index 0. Window capture
     * failures surface as [IOException] rather than silently returning a full-desktop grab (#289).
     *
     * @return raw PNG bytes
     */
    @Throws(IOException::class)
    public fun screenshot(
        windowIndex: Int? = null,
        surfaceId: String? = null,
        fullscreen: Boolean = false,
    ): ByteArray {
        val resp =
            exchange(
                AgentRequest.Screenshot(
                    windowIndex = windowIndex,
                    surfaceId = surfaceId,
                    fullscreen = fullscreen,
                )
            )
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
     * Describe native window identity for daemon-side recording.
     *
     * @param windowIndex when null, every tracked window; when set, only that index (empty if out
     *   of range).
     */
    @Throws(IOException::class)
    public fun windowIdentities(windowIndex: Int? = null): List<WindowIdentityDto> {
        val resp = exchange(AgentRequest.WindowIdentity(windowIndex))
        return (resp as? AgentResponse.WindowIdentities)?.windows
            ?: throw wireMismatch("WindowIdentities", resp)
    }

    /**
     * Wait until a semantics node matches [tag] and/or [text] (AND when both are set), same as
     * in-process `ComposeAutomator.waitForNode` (#201).
     *
     * Propagates [timeoutMs] as an absolute deadline on the wire so the agent and client share one
     * budget. Throws [SpectreAgentException] with category `timeout` when the wait expires.
     */
    @Throws(IOException::class)
    public fun waitForNode(
        tag: String? = null,
        text: String? = null,
        timeoutMs: Long = 5_000,
        pollIntervalMs: Long = 100,
    ): NodeSnapshotDto {
        val deadline = System.currentTimeMillis() + timeoutMs
        val resp =
            exchange(
                AgentRequest.WaitForNode(
                    tag = tag,
                    text = text,
                    timeoutMs = timeoutMs,
                    pollIntervalMs = pollIntervalMs,
                ),
                deadlineEpochMs = deadline,
            )
        val nodes = (resp as? AgentResponse.Nodes)?.nodes ?: throw wireMismatch("Nodes", resp)
        return nodes.singleOrNull()
            ?: throw IOException("waitForNode expected a single node, got ${nodes.size}")
    }

    /**
     * Wait until consecutive visual frames are stable (#201). Same semantics as in-process
     * `ComposeAutomator.waitForVisualIdle`.
     */
    @Throws(IOException::class)
    public fun waitForVisualIdle(
        timeoutMs: Long = 5_000,
        stableFrames: Int = 3,
        pollIntervalMs: Long = 16,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val resp =
            exchange(
                AgentRequest.WaitForVisualIdle(
                    timeoutMs = timeoutMs,
                    stableFrames = stableFrames,
                    pollIntervalMs = pollIntervalMs,
                ),
                deadlineEpochMs = deadline,
            )
        if (resp !is AgentResponse.Ok) throw wireMismatch("Ok", resp)
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

    private fun exchange(request: AgentRequest, deadlineEpochMs: Long? = null): AgentResponse {
        check(!closed.get()) { "AttachedAutomator is closed" }
        val resp = client.send(request, deadlineEpochMs = deadlineEpochMs)
        if (resp is AgentResponse.Error) {
            val category =
                dev.sebastiano.spectre.agent.transport.AgentErrorCategory.fromWire(resp.category)
            throw SpectreAgentException(
                category = category,
                message =
                    "Agent reported ${category.wireName} for ${request.logLabel}: ${resp.message}",
            )
        }
        return resp
    }

    private fun wireMismatch(expected: String, actual: AgentResponse): IOException =
        IOException("Wire protocol mismatch: expected $expected, got ${actual::class.simpleName}")
}

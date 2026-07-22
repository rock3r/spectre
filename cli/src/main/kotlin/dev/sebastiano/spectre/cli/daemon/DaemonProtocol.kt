package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/** Shared client/daemon wire protocol metadata for Spectre's agent-facing entrypoints. */
@OptIn(ExperimentalSerializationApi::class)
public object DaemonProtocol {
    public val CurrentVersion: DaemonProtocolVersion = DaemonProtocolVersion(major = 1, minor = 11)

    public val cbor: Cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun checkCompatibility(
        client: DaemonProtocolVersion,
        daemon: DaemonProtocolVersion,
    ): VersionCompatibility =
        when {
            client.major != daemon.major -> VersionCompatibility.MajorMismatch
            daemon.minor < client.minor -> VersionCompatibility.DaemonTooOld
            else -> VersionCompatibility.Compatible
        }

    internal fun minimumDaemonVersion(request: DaemonRequest): DaemonProtocolVersion =
        when (request) {
            is DaemonRequest.Hello -> versionFor(MINIMUM_PROTOCOL_MINOR)
            is DaemonRequest.Attach,
            is DaemonRequest.Detach,
            DaemonRequest.ListSessions,
            DaemonRequest.Shutdown -> versionFor(SESSION_LIFECYCLE_INTRODUCED_MINOR)
            is DaemonRequest.Windows,
            is DaemonRequest.AllNodes,
            is DaemonRequest.FindByTestTag,
            is DaemonRequest.Click,
            is DaemonRequest.TypeText -> versionFor(SESSION_COMMANDS_INTRODUCED_MINOR)
            is DaemonRequest.DoubleClick,
            is DaemonRequest.LongClick,
            is DaemonRequest.Swipe,
            is DaemonRequest.ScrollWheel,
            is DaemonRequest.PressKey -> versionFor(INPUT_VERBS_INTRODUCED_MINOR)
            is DaemonRequest.WaitForNode,
            is DaemonRequest.WaitForVisualIdle -> versionFor(WAIT_OPS_INTRODUCED_MINOR)
            is DaemonRequest.WaitForReloadSettled -> versionFor(RELOAD_SETTLE_INTRODUCED_MINOR)
            is DaemonRequest.FindByText,
            is DaemonRequest.FindByContentDescription,
            is DaemonRequest.FindByRole -> versionFor(SELECTOR_PARITY_INTRODUCED_MINOR)
            is DaemonRequest.ListJvmProcesses -> versionFor(LIST_JVM_PROCESSES_INTRODUCED_MINOR)
            is DaemonRequest.StartRecording ->
                if (request.fullscreen) {
                    versionFor(RECORDING_FULLSCREEN_INTRODUCED_MINOR)
                } else {
                    versionFor(RECORDING_SESSION_INTRODUCED_MINOR)
                }
            is DaemonRequest.StopRecording,
            is DaemonRequest.RecordingStatus -> versionFor(RECORDING_SESSION_INTRODUCED_MINOR)
            is DaemonRequest.Capture -> versionFor(CAPTURE_INTRODUCED_MINOR)
            is DaemonRequest.Screenshot -> versionFor(SCREENSHOT_TARGETING_INTRODUCED_MINOR)
        }

    private fun versionFor(minor: Int): DaemonProtocolVersion =
        DaemonProtocolVersion(major = CurrentVersion.major, minor = minor)

    private const val MINIMUM_PROTOCOL_MINOR: Int = 0
    private const val SESSION_LIFECYCLE_INTRODUCED_MINOR: Int = 1
    private const val SESSION_COMMANDS_INTRODUCED_MINOR: Int = 2
    private const val LIST_JVM_PROCESSES_INTRODUCED_MINOR: Int = 3
    private const val CAPTURE_INTRODUCED_MINOR: Int = 5
    /** Optional outputPath, windowIndex, and recordingStatus (#185). */
    private const val RECORDING_SESSION_INTRODUCED_MINOR: Int = 6
    /** Window/surface/fullscreen screenshot targeting (#289). */
    private const val SCREENSHOT_TARGETING_INTRODUCED_MINOR: Int = 7
    /** Full-desktop recording opt-in (`StartRecording.fullscreen`). */
    private const val RECORDING_FULLSCREEN_INTRODUCED_MINOR: Int = 8
    /** Richer input verbs: doubleClick / longClick / swipe / scrollWheel / pressKey (#203). */
    private const val INPUT_VERBS_INTRODUCED_MINOR: Int = 9
    /** waitForNode / waitForVisualIdle and selector finders over daemon (#201/#202). */
    private const val WAIT_OPS_INTRODUCED_MINOR: Int = 10
    private const val SELECTOR_PARITY_INTRODUCED_MINOR: Int = 10
    /** waitForReloadSettled for Compose Hot Reload awareness (#211). */
    private const val RELOAD_SETTLE_INTRODUCED_MINOR: Int = 11
}

@Serializable public data class DaemonProtocolVersion(public val major: Int, public val minor: Int)

public enum class VersionCompatibility {
    Compatible,
    MajorMismatch,
    DaemonTooOld,
}

@OptIn(ExperimentalSpectreAgentApi::class)
@Serializable
public sealed interface DaemonRequest {
    @Serializable
    public data class Hello(public val clientVersion: DaemonProtocolVersion) : DaemonRequest

    @Serializable
    @SerialName("attach")
    public data class Attach(public val targetPid: Long) : DaemonRequest

    @Serializable
    @SerialName("detach")
    public data class Detach(public val sessionId: String) : DaemonRequest

    @Serializable @SerialName("listSessions") public data object ListSessions : DaemonRequest

    @Serializable
    @SerialName("listJvmProcesses")
    public data class ListJvmProcesses(public val requesterPid: Long) : DaemonRequest

    @Serializable
    @SerialName("windows")
    public data class Windows(public val sessionId: String) : DaemonRequest

    @Serializable
    @SerialName("allNodes")
    public data class AllNodes(public val sessionId: String) : DaemonRequest

    @Serializable
    @SerialName("findByTestTag")
    public data class FindByTestTag(public val sessionId: String, public val tag: String) :
        DaemonRequest

    @Serializable
    @SerialName("click")
    public data class Click(public val sessionId: String, public val nodeKey: String) :
        DaemonRequest

    @Serializable
    @SerialName("doubleClick")
    public data class DoubleClick(public val sessionId: String, public val nodeKey: String) :
        DaemonRequest

    @Serializable
    @SerialName("longClick")
    public data class LongClick(
        public val sessionId: String,
        public val nodeKey: String,
        public val holdForMs: Long = 500,
    ) : DaemonRequest

    @Serializable
    @SerialName("swipe")
    public data class Swipe(
        public val sessionId: String,
        public val fromNodeKey: String? = null,
        public val toNodeKey: String? = null,
        public val startX: Int? = null,
        public val startY: Int? = null,
        public val endX: Int? = null,
        public val endY: Int? = null,
        public val steps: Int = 12,
        public val durationMs: Long = 200,
    ) : DaemonRequest

    @Serializable
    @SerialName("scrollWheel")
    public data class ScrollWheel(
        public val sessionId: String,
        public val nodeKey: String,
        public val wheelClicks: Int,
    ) : DaemonRequest

    @Serializable
    @SerialName("pressKey")
    public data class PressKey(
        public val sessionId: String,
        public val keyCode: Int,
        public val modifiers: Int = 0,
    ) : DaemonRequest

    @Serializable
    @SerialName("typeText")
    public data class TypeText(public val sessionId: String, public val text: String) :
        DaemonRequest

    @Serializable
    @SerialName("findByText")
    public data class FindByText(
        public val sessionId: String,
        public val text: String,
        public val exact: Boolean = true,
    ) : DaemonRequest

    @Serializable
    @SerialName("findByContentDescription")
    public data class FindByContentDescription(
        public val sessionId: String,
        public val description: String,
    ) : DaemonRequest

    @Serializable
    @SerialName("findByRole")
    public data class FindByRole(public val sessionId: String, public val role: String) :
        DaemonRequest

    @Serializable
    @SerialName("waitForNode")
    public data class WaitForNode(
        public val sessionId: String,
        public val tag: String? = null,
        public val text: String? = null,
        public val timeoutMs: Long = 5_000,
        public val pollIntervalMs: Long = 100,
    ) : DaemonRequest

    @Serializable
    @SerialName("waitForVisualIdle")
    public data class WaitForVisualIdle(
        public val sessionId: String,
        public val timeoutMs: Long = 5_000,
        public val stableFrames: Int = 3,
        public val pollIntervalMs: Long = 16,
    ) : DaemonRequest

    /**
     * Wait until a Compose Hot Reload settle chain completes (#211).
     *
     * Available only when the attach session is reload-aware. Failures carry
     * a #199-style [DaemonResponse.Error.category] (`hotReloadUnavailable` / `reloadFailed` /
     * `timeout` / `cancelled`).
     */
    @Serializable
    @SerialName("waitForReloadSettled")
    public data class WaitForReloadSettled(
        public val sessionId: String,
        public val timeoutMs: Long = 60_000,
    ) : DaemonRequest

    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(
        public val sessionId: String,
        public val windowIndex: Int? = null,
        public val surfaceId: String? = null,
        public val fullscreen: Boolean = false,
    ) : DaemonRequest

    @Serializable
    @SerialName("capture")
    public data class Capture(
        public val sessionId: String,
        public val windowIndex: Int = 0,
        public val outDir: String? = null,
    ) : DaemonRequest

    @Serializable
    @SerialName("startRecording")
    public data class StartRecording(
        public val sessionId: String,
        /** Absolute path to the .mp4, or null to allocate under the capture root. */
        public val outputPath: String? = null,
        /** Tracked window index when [fullscreen] is false. Ignored when [fullscreen] is true. */
        public val windowIndex: Int = 0,
        /**
         * When true, record the full primary display via region capture instead of a window.
         * Multi-monitor desktops are rejected (backends are single-display). Must not be combined
         * with a non-default window target at the CLI/MCP layer.
         */
        public val fullscreen: Boolean = false,
    ) : DaemonRequest

    @Serializable
    @SerialName("stopRecording")
    public data class StopRecording(public val sessionId: String) : DaemonRequest

    @Serializable
    @SerialName("recordingStatus")
    public data class RecordingStatus(public val sessionId: String) : DaemonRequest

    @Serializable @SerialName("shutdown") public data object Shutdown : DaemonRequest
}

@OptIn(ExperimentalSpectreAgentApi::class)
@Serializable
public sealed interface DaemonResponse {
    @Serializable
    public data class Hello(public val daemonVersion: DaemonProtocolVersion) : DaemonResponse

    @Serializable
    @SerialName("attached")
    public data class Attached(public val sessionId: String, public val targetPid: Long) :
        DaemonResponse

    @Serializable
    @SerialName("detached")
    public data class Detached(
        public val sessionId: String,
        /** Captures this session left on disk (existing directories only). */
        public val captureCount: Int = 0,
        public val captureBytes: Long = 0L,
        public val capturePaths: List<String> = emptyList(),
        /** Exact CLI command to prune only this session's leftover captures. */
        public val pruneCommand: String? = null,
        /** Agent skill name that documents capture.json + jq workflows. */
        public val skillHint: String? = null,
    ) : DaemonResponse

    @Serializable
    @SerialName("sessions")
    public data class Sessions(public val sessions: List<DaemonSessionSummary>) : DaemonResponse

    @Serializable
    @SerialName("jvmProcesses")
    public data class JvmProcesses(public val processes: List<DaemonJvmProcessSummary>) :
        DaemonResponse

    @Serializable
    @SerialName("windows")
    public data class Windows(
        public val sessionId: String,
        public val windows: List<WindowSummaryDto>,
    ) : DaemonResponse

    @Serializable
    @SerialName("nodes")
    public data class Nodes(public val sessionId: String, public val nodes: List<NodeSnapshotDto>) :
        DaemonResponse

    @Serializable
    @SerialName("completed")
    public data class Completed(public val sessionId: String) : DaemonResponse

    @Serializable
    @SerialName("screenshot")
    public data class Screenshot(public val sessionId: String, public val pngBytes: ByteArray) :
        DaemonResponse {
        override fun equals(other: Any?): Boolean =
            other is Screenshot &&
                sessionId == other.sessionId &&
                pngBytes.contentEquals(other.pngBytes)

        override fun hashCode(): Int = 31 * sessionId.hashCode() + pngBytes.contentHashCode()
    }

    /**
     * Atomic capture completed: artifacts written under [directory], with decision-grade summary
     * fields for inline agent responses.
     */
    @Serializable
    @SerialName("capture")
    public data class Capture(
        public val sessionId: String,
        public val directory: String,
        public val captureJsonPath: String,
        public val screenshotPngPath: String,
        public val schemaVersion: Int,
        public val windowIndex: Int,
        public val nodeCount: Int,
        public val taggedNodeCount: Int,
        public val textedNodeCount: Int,
        public val imageWidth: Int,
        public val imageHeight: Int,
        public val captureDurationMs: Long,
    ) : DaemonResponse

    @Serializable
    @SerialName("recordingStarted")
    public data class RecordingStarted(
        public val sessionId: String,
        public val outputPath: String,
    ) : DaemonResponse

    @Serializable
    @SerialName("recordingStopped")
    public data class RecordingStopped(
        public val sessionId: String,
        public val outputPath: String,
    ) : DaemonResponse

    @Serializable
    @SerialName("recordingStatus")
    public data class RecordingStatus(
        public val sessionId: String,
        public val active: Boolean,
        public val outputPath: String? = null,
        public val captureDirectory: String? = null,
    ) : DaemonResponse

    @Serializable @SerialName("shuttingDown") public data object ShuttingDown : DaemonResponse

    @Serializable
    @SerialName("error")
    public data class Error(
        public val code: DaemonErrorCode,
        public val message: String,
        /**
         * Optional #199 taxonomy wire name (e.g. `timeout`, `hotReloadUnavailable`). Null for
         * pre-taxonomy daemon failures.
         */
        public val category: String? = null,
    ) : DaemonResponse
}

@Serializable
public data class DaemonSessionSummary(public val sessionId: String, public val targetPid: Long)

/** A JVM process that can be targeted through the local daemon. */
@Serializable
public data class DaemonJvmProcessSummary(public val pid: Long, public val displayName: String)

public enum class DaemonErrorCode {
    SessionNotFound,
    AttachFailed,
    ProtocolError,
    ShutdownInProgress,
    OperationFailed,
    /** Target is not running under Compose Hot Reload (#211). */
    HotReloadUnavailable,
    /** HR reported a failed class reload (#211). */
    ReloadFailed,
    /** Wait exceeded its deadline (#199 / #211). */
    Timeout,
}

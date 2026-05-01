package dev.sebastiano.spectre.recording.portal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Wire protocol for the [`spectre-wayland-helper`](recording/native/linux/) binary.
 *
 * Helper lifecycle (helper view): receive a [Command.Start] on stdin → portal handshake +
 * `OpenPipeWireRemote` → spawn `gst-launch-1.0` with the FD inherited → emit [Event.Started] → wait
 * for [Command.Stop] (or stdin EOF) → SIGTERM gst-launch with `-e` for clean mux finalisation →
 * emit [Event.Stopped] (or [Event.Error] on any failure) → exit.
 *
 * Wire format: newline-delimited JSON over stdin (commands JVM → helper) and stdout (events helper
 * → JVM). One command or event per line. Helper-side stderr is forwarded to the JVM's stderr (or
 * wherever the JVM redirected it) for diagnostic logging.
 *
 * Field names use snake_case via `@SerialName` so the helper's [serde-rs](https://serde.rs) default
 * rename rule produces a wire format the JVM also accepts. The discriminator field is `command`
 * (for [Command]) and `event` (for [Event]) — match the helper's `#[serde(tag = "command")]` /
 * `#[serde(tag = "event")]` annotations.
 */
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("command")
internal sealed interface Command {

    @Serializable
    @SerialName("start")
    data class Start(
        @SerialName("source_types") val sourceTypes: List<SourceType>,
        @SerialName("cursor_mode") val cursorMode: CursorMode,
        @SerialName("frame_rate") val frameRate: Int,
        @SerialName("region") val region: Region,
        @SerialName("output") val output: String,
        @SerialName("codec") val codec: String,
    ) : Command

    @Serializable @SerialName("stop") data object Stop : Command
}

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonClassDiscriminator("event")
internal sealed interface Event {

    @Serializable
    @SerialName("started")
    data class Started(
        @SerialName("node_id") val nodeId: Long,
        @SerialName("stream_size") val streamSize: List<Long>,
        @SerialName("stream_position") val streamPosition: List<Long>,
        @SerialName("gst_pid") val gstPid: Long,
    ) : Event

    @Serializable
    @SerialName("frame_progress")
    data class FrameProgress(@SerialName("frames") val frames: Long) : Event

    @Serializable
    @SerialName("stopped")
    data class Stopped(@SerialName("output_size_bytes") val outputSizeBytes: Long) : Event

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("kind") val kind: String,
        @SerialName("message") val message: String,
    ) : Event
}

@Serializable
internal enum class SourceType {
    @SerialName("monitor") MONITOR,
    @SerialName("window") WINDOW,
    @SerialName("virtual") VIRTUAL,
}

@Serializable
internal enum class CursorMode {
    @SerialName("hidden") HIDDEN,
    @SerialName("embedded") EMBEDDED,
    @SerialName("metadata") METADATA,
}

@Serializable internal data class Region(val x: Int, val y: Int, val width: Int, val height: Int)

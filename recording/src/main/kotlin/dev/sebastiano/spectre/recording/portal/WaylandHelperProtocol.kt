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
        @SerialName("backend") val backend: CaptureBackend = CaptureBackend.WAYLAND_PORTAL,
        @SerialName("target") val target: CaptureTarget = CaptureTarget.REGION,
        @SerialName("source_types") val sourceTypes: List<SourceType>,
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("window_title") val windowTitle: String? = null,
        @SerialName("cursor_mode") val cursorMode: CursorMode,
        @SerialName("frame_rate") val frameRate: Int,
        @SerialName("region") val region: Region,
        @SerialName("screen_size") val screenSize: List<Int>? = null,
        @SerialName("output") val output: String,
        @SerialName("codec") val codec: String,
    ) : Command

    @Serializable
    @SerialName("screenshot")
    data class Screenshot(
        @SerialName("backend") val backend: CaptureBackend,
        @SerialName("target") val target: CaptureTarget,
        @SerialName("source_types") val sourceTypes: List<SourceType> = emptyList(),
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("window_title") val windowTitle: String? = null,
        @SerialName("cursor_mode") val cursorMode: CursorMode,
        @SerialName("region") val region: Region,
        @SerialName("screen_size") val screenSize: List<Int>? = null,
        @SerialName("output") val output: String,
    ) : Command

    @Serializable @SerialName("stop") data object Stop : Command

    @Serializable
    @SerialName("pointer_move")
    data class PointerMove(@SerialName("x") val x: Int, @SerialName("y") val y: Int) : Command

    @Serializable
    @SerialName("pointer_button")
    data class PointerButton(
        @SerialName("button") val button: Int,
        @SerialName("pressed") val pressed: Boolean,
    ) : Command

    @Serializable
    @SerialName("key")
    data class Key(
        @SerialName("key_code") val keyCode: Int,
        @SerialName("pressed") val pressed: Boolean,
    ) : Command

    @Serializable
    @SerialName("pointer_axis")
    data class PointerAxis(@SerialName("axis") val axis: Int, @SerialName("steps") val steps: Int) :
        Command
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
    @SerialName("screenshot_saved")
    data class ScreenshotSaved(@SerialName("output_size_bytes") val outputSizeBytes: Long) : Event

    @Serializable
    @SerialName("error")
    data class Error(
        @SerialName("kind") val kind: String,
        @SerialName("message") val message: String,
    ) : Event

    @Serializable @SerialName("input_ack") data object InputAck : Event
}

@Serializable
internal enum class CaptureBackend {
    @SerialName("wayland_portal") WAYLAND_PORTAL,
    @SerialName("x11") X11,
}

@Serializable
internal enum class CaptureTarget {
    @SerialName("region") REGION,
    @SerialName("window") WINDOW,
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

internal const val RD_TOKEN_FILE_PREFIX: String = "wayland-rd-restore-token"

internal fun remoteDesktopTokenFileName(tokenKey: String): String =
    "$RD_TOKEN_FILE_PREFIX-$tokenKey"

internal data class WaylandSessionPaths(
    val lock: java.nio.file.Path,
    val socket: java.nio.file.Path,
)

internal fun waylandSessionPaths(dir: java.nio.file.Path): WaylandSessionPaths =
    WaylandSessionPaths(
        lock = dir.resolve("wayland-session.lock"),
        socket = dir.resolve("wayland-session.sock"),
    )

internal const val VERTICAL_POINTER_AXIS: Int = 0
internal const val SESSION_SOCKET_POLL_MS: Long = 50
internal const val WAYLAND_SESSION_OWNED_EXIT: Int = 75

internal fun isFatalWaylandHelperExit(exitCode: Int): Boolean =
    exitCode != 0 && exitCode != WAYLAND_SESSION_OWNED_EXIT

internal fun Process?.isFatalSessionExit(): Boolean {
    if (this == null || isAlive) return false
    return isFatalWaylandHelperExit(exitValue())
}

internal fun resolveWaylandSessionSocket(
    paths: WaylandSessionPaths,
    socketIsLive: (java.nio.file.Path) -> Boolean,
    startHelper: () -> Unit,
    waitForSocket: (java.nio.file.Path, Long) -> Boolean,
    timeoutMs: Long,
    helperExited: () -> Boolean = { false },
    helperExitDetail: () -> String = { "exited before binding the session socket" },
): java.nio.file.Path {
    if (java.nio.file.Files.exists(paths.socket) && socketIsLive(paths.socket)) {
        return paths.socket
    }
    java.nio.file.Files.deleteIfExists(paths.socket)
    startHelper()
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (waitForSocket(paths.socket, SESSION_SOCKET_POLL_MS)) {
            return paths.socket
        }
        check(!helperExited()) { "spectre-wayland-helper --session ${helperExitDetail()}" }
    }
    check(!helperExited()) { "spectre-wayland-helper --session ${helperExitDetail()}" }
    error(
        "spectre-wayland-helper --session did not create ${paths.socket} within ${timeoutMs}ms. " +
            "Accept the compositor Share / Allow remote interaction dialog if it is waiting, " +
            "and check that xdg-desktop-portal is running. If a previous helper died, delete a " +
            "stale ${paths.socket} and retry."
    )
}

internal fun isRestoreTokenRejection(error: Throwable): Boolean {
    val msg = error.message.orEmpty()
    return (msg.contains("SelectSources rejected") ||
        msg.contains("SelectDevices rejected") ||
        msg.contains("Start rejected")) &&
        (msg.contains("restore_token") ||
            msg.contains("stored restore_token") ||
            msg.contains("no longer valid") ||
            msg.contains("response code"))
}

internal fun restoreRemoteDesktopGrant(
    tokenKey: String,
    loadToken: () -> String?,
    clearToken: (String) -> Unit,
    startWithToken: (String?) -> Result<String>,
): String {
    val stored = loadToken()
    val first = startWithToken(stored)
    val granted = first.getOrNull()
    if (granted != null) return granted
    val firstError =
        first.exceptionOrNull() ?: error("RemoteDesktop start failed without an exception")
    if (stored == null || !isRestoreTokenRejection(firstError)) {
        throw firstError as? RuntimeException
            ?: IllegalStateException("RemoteDesktop session failed", firstError)
    }
    clearToken(tokenKey)
    return startWithToken(null).getOrElse { second ->
        throw IllegalStateException(
                "interactive RemoteDesktop retry after invalid restore_token also failed " +
                    "(original: ${firstError.message})",
                second,
            )
            .apply { addSuppressed(firstError) }
    }
}

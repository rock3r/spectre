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
        /** Untitled-window selector: root coordinates, device pixels. X11 only. */
        @SerialName("window_frame") val windowFrame: Region? = null,
        @SerialName("window_owner_pid") val windowOwnerPid: Long? = null,
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

    /**
     * Session only. Binds a GNOME Mutter window stream to the window that has keyboard focus and
     * writes its first frame to [output] as an RGBA PNG.
     */
    @Serializable
    @SerialName("bind_focused_window")
    data class BindFocusedWindow(
        @SerialName("key") val key: String,
        @SerialName("output") val output: String,
        /** Mutter fixes the cursor mode when it binds the stream. */
        @SerialName("cursor_mode") val cursorMode: CursorMode = CursorMode.HIDDEN,
    ) : Command

    /** Session only. Writes one RGBA PNG frame of the window bound under [key]. */
    @Serializable
    @SerialName("window_screenshot")
    data class WindowScreenshot(
        @SerialName("key") val key: String,
        @SerialName("output") val output: String,
    ) : Command

    /** Session only. Records the window bound under [key], cropped to [region] (stream pixels). */
    @Serializable
    @SerialName("start_window_recording")
    data class StartWindowRecording(
        @SerialName("key") val key: String,
        @SerialName("region") val region: Region,
        @SerialName("stream_size") val streamSize: List<Int>,
        @SerialName("frame_rate") val frameRate: Int,
        @SerialName("output") val output: String,
        @SerialName("codec") val codec: String,
    ) : Command

    /** Session only. Stops the Mutter stream bound under [key]. */
    @Serializable
    @SerialName("unbind_window")
    data class UnbindWindow(@SerialName("key") val key: String) : Command
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

    @Serializable
    @SerialName("window_bound")
    data class WindowBound(
        @SerialName("key") val key: String,
        @SerialName("node_id") val nodeId: Long,
        /** Mutter's stream `size`: on GNOME 50 the monitor's logical size, not the window's. */
        @SerialName("logical_size") val logicalSize: List<Int>,
    ) : Event

    @Serializable
    @SerialName("window_unbound")
    data class WindowUnbound(
        @SerialName("key") val key: String,
        @SerialName("was_bound") val wasBound: Boolean,
    ) : Event
}

/** Helper error kind for a desktop without GNOME Mutter's ScreenCast API. */
internal const val HELPER_ERROR_MUTTER_UNAVAILABLE: String = "MutterUnavailable"

/** Helper error kind for a window key the session helper has no stream for. */
internal const val HELPER_ERROR_WINDOW_NOT_BOUND: String = "WindowNotBound"

/** An `Event.Error` from the session helper, with its [kind] kept for callers that branch on it. */
internal class WaylandHelperErrorException(val kind: String, helperMessage: String) :
    IllegalStateException("spectre-wayland-helper session error: kind=$kind message=$helperMessage")

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

/**
 * Exit status for a `--session` helper whose Share / Allow remote interaction dialog timed out or
 * was denied. Mirrors `CONSENT_NOT_GRANTED_EXIT` in the helper's `session.rs`.
 */
internal const val WAYLAND_SESSION_CONSENT_EXIT: Int = 77

internal fun isFatalWaylandHelperExit(exitCode: Int): Boolean =
    exitCode != 0 && exitCode != WAYLAND_SESSION_OWNED_EXIT

/** Exit status of a session helper that has exited, or null while it runs or was never started. */
internal fun Process?.sessionExitCode(): Int? = this?.takeUnless { it.isAlive }?.exitValue()

/**
 * Linux Wayland capture or input cannot run, and waiting will not help: the caller must fail at
 * once with this message instead of retrying or substituting another capture mechanism.
 *
 * Core matches this class by name (it cannot link `:recording`), so visual-idle sampling rethrows
 * it instead of counting an unsampleable frame. Keep the name in sync with
 * `WAYLAND_CAPTURE_FAIL_FAST_EXCEPTIONS` in core's `VisualIdleSurfaceCapture.kt`.
 */
internal open class WaylandCaptureUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * The session helper could not open the portal session because nobody answered the consent dialog
 * in time, the user denied it, or Spectre may not open the dialog in this run (see
 * [WaylandConsentPrompt]). [WaylandSessionClient] remembers it so later captures in this JVM fail
 * at once instead of opening a new dialog each time (#634).
 */
internal class WaylandConsentNotGrantedException(message: String, cause: Throwable? = null) :
    WaylandCaptureUnavailableException(message, cause)

/** The one-time fix for a missing Wayland grant. Core's fail-fast check also looks for it. */
internal const val WAYLAND_PERMISSIONS_REQUEST_HINT: String =
    "Run `spectre permissions request` once while someone is at the seat: select the whole " +
        "screen, tick Remember, and click Share. Spectre stores the grant and keeps the session " +
        "running, so later runs do not ask again."

internal fun waylandConsentNotGrantedMessage(): String =
    "Wayland screen capture needs consent, but the Share / Allow remote interaction dialog was " +
        "not answered in time or was denied (spectre-wayland-helper --session exit " +
        "$WAYLAND_SESSION_CONSENT_EXIT; its output above has the portal error). Spectre does not " +
        "open the dialog again in this JVM, so later captures fail at once. " +
        "$WAYLAND_PERMISSIONS_REQUEST_HINT Then rerun in a new JVM. If no dialog is visible, " +
        "open the Activities overview (Super key): GNOME can open it behind other windows."

/** The message for a run that may not open the Share dialog and has no stored grant. */
internal fun waylandNoStoredGrantMessage(tokenPath: java.nio.file.Path?): String =
    "Wayland screen capture and input need the RemoteDesktop portal grant, but no grant is " +
        "stored (${tokenPath ?: "no restore-token directory: HOME and XDG_STATE_HOME are unset"}) " +
        "and no Spectre session is running. This run does not open the Share dialog itself " +
        "($WAYLAND_CONSENT_PROMPT_PROPERTY / $WAYLAND_CONSENT_PROMPT_ENV is never). " +
        WAYLAND_PERMISSIONS_REQUEST_HINT

internal fun resolveWaylandSessionSocket(
    paths: WaylandSessionPaths,
    socketIsLive: (java.nio.file.Path) -> Boolean,
    startHelper: () -> Unit,
    waitForSocket: (java.nio.file.Path, Long) -> Boolean,
    timeoutMs: Long,
    helperExitCode: () -> Int? = { null },
): java.nio.file.Path {
    if (java.nio.file.Files.exists(paths.socket) && socketIsLive(paths.socket)) {
        return paths.socket
    }
    // Do not unlink here. A concurrent helper can bind between this miss and deleteIfExists,
    // which would remove the winner's live socket. The native process unlinks leftovers after
    // it acquires the session flock.
    startHelper()
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (socketIsLive(paths.socket)) {
            return paths.socket
        }
        if (waitForSocket(paths.socket, SESSION_SOCKET_POLL_MS) && socketIsLive(paths.socket)) {
            return paths.socket
        }
        failIfHelperExitedFatally(helperExitCode())
    }
    failIfHelperExitedFatally(helperExitCode())
    error(
        "spectre-wayland-helper --session did not create ${paths.socket} within ${timeoutMs}ms. " +
            "Accept the compositor Share / Allow remote interaction dialog if it is waiting, " +
            "and check that xdg-desktop-portal is running. If a previous helper died, delete a " +
            "stale ${paths.socket} and retry."
    )
}

internal fun failIfHelperExitedFatally(exitCode: Int?) {
    if (exitCode == WAYLAND_SESSION_CONSENT_EXIT) {
        throw WaylandConsentNotGrantedException(waylandConsentNotGrantedMessage())
    }
    check(exitCode == null || !isFatalWaylandHelperExit(exitCode)) {
        "spectre-wayland-helper --session exited before binding the session socket (exit $exitCode)"
    }
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

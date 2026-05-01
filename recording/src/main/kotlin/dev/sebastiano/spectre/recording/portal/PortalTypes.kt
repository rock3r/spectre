package dev.sebastiano.spectre.recording.portal

import org.freedesktop.dbus.connections.impl.DBusConnection

/**
 * One source-type bit in `org.freedesktop.portal.ScreenCast`'s SelectSources `types` option.
 *
 * The full virtual-desktop case (entire screen / monitor) is [MONITOR]. [WINDOW] lets the user pick
 * a specific window — useful for window-targeted recording, but not how Spectre's region recording
 * is structured (we record the screen and crop server-side via the PipeWire stream's own coordinate
 * space). [VIRTUAL] is for compositor-injected virtual screens (multi-seat, KVM).
 */
// The bitfield values below come straight from the xdg-desktop-portal spec
// (https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.ScreenCast.html).
// They're protocol constants, not arbitrary numbers, so the MagicNumber detekt rule is
// suppressed file-locally rather than per-declaration.
@Suppress("MagicNumber")
internal enum class SourceType(val flag: Int) {
    MONITOR(1),
    WINDOW(2),
    VIRTUAL(4),
}

/** Cursor-mode bit for SelectSources `cursor_mode`. */
@Suppress("MagicNumber")
internal enum class CursorMode(val flag: Int) {
    /** Cursor not drawn into the captured frames. */
    HIDDEN(1),
    /** Cursor pixels baked into the frames — matches `RecordingOptions.captureCursor=true`. */
    EMBEDDED(2),
    /**
     * Cursor delivered as out-of-band metadata (PipeWire properties on each frame). Only useful for
     * clients that overlay the cursor themselves; we don't.
     */
    METADATA(4),
}

/** Persistence policy for the portal grant — see SelectSources `persist_mode`. */
@Suppress("MagicNumber")
internal enum class PersistMode(val flag: Int) {
    /** No persistence — each connection re-prompts the user. */
    NO(0),
    /** Persisted for the running app instance — we ask again on next run. */
    TRANSIENT(1),
    /** Stored across logins. */
    PERSISTENT(2),
}

/** Convert a set of source-type flags to the D-Bus `u` bitmask the portal expects. */
internal fun Set<SourceType>.toBitmask(): Int = fold(0) { acc, t -> acc or t.flag }

/** Raised when a portal Request never produces a Response within the configured timeout. */
internal class PortalTimeoutException(message: String) : RuntimeException(message)

/**
 * Raised when the portal's Response shape doesn't match what the spec promises (missing
 * `session_handle`, malformed `streams`, etc.). Indicates a portal-side regression or a compositor
 * that doesn't implement the version of the spec we're targeting.
 */
internal class PortalProtocolException(message: String) : RuntimeException(message)

/**
 * Active portal screen-cast session. Holds the D-Bus connection that owns the session and the
 * PipeWire stream id ffmpeg needs.
 *
 * **Closing matters**: dropping the [DBusConnection] tells the portal to tear the screen-cast down,
 * which is what we want. If the connection were leaked, the compositor would keep the screen-share
 * UI active (e.g. GNOME's status bar indicator) until the JVM exited.
 */
internal class PortalSession(
    val nodeId: Int,
    val position: Pair<Int, Int>,
    val size: Pair<Int, Int>,
    val sessionHandle: String,
    private val connection: DBusConnection,
) : AutoCloseable {

    @Volatile private var closed: Boolean = false

    val isClosed: Boolean
        get() = closed

    override fun close() {
        if (closed) return
        closed = true
        // We don't explicitly call `org.freedesktop.portal.Session.Close()` — closing the
        // connection drops the session as a side effect (the portal binds the session lifetime
        // to its creating client connection). Explicit Close() would require an extra
        // round-trip and another signal subscription; the connection-close shortcut is cleaner
        // and is what the portal docs recommend for short-lived clients.
        runCatching { connection.close() }
    }
}

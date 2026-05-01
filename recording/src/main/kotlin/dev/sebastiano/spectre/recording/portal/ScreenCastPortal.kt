package dev.sebastiano.spectre.recording.portal

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * Driver for `org.freedesktop.portal.ScreenCast` (the xdg-desktop-portal interface that GNOME, KDE,
 * and wlroots-with-xdpw all expose for screen-share applications). Encapsulates the three D-Bus
 * method calls that the portal requires to grant a screen-cast — `CreateSession`, `SelectSources`,
 * `Start` — and the asynchronous `Request.Response` signals that carry the actual results.
 *
 * Returns a [PortalSession] holding the PipeWire stream node id to feed to ffmpeg's `pipewiregrab`
 * device, plus an [AutoCloseable] handle that tears the portal session down cleanly when the caller
 * stops recording.
 *
 * Implementation notes:
 *
 * The portal uses a Request/Response pattern: each method returns a `Request` object path
 * immediately, and the actual result lands later as a `Response` signal on that path. The Request
 * path is deterministic — it's `/org/freedesktop/portal/desktop/request/<sender>/<token>` where
 * `<sender>` is the calling D-Bus connection's unique name (with the leading `:` stripped and dots
 * replaced by underscores) and `<token>` is the `handle_token` we pass in. We compute the path
 * ourselves and subscribe to the signal _before_ making the call to avoid a race where the portal
 * could deliver the Response between our call returning and our handler being registered.
 *
 * Connection lifecycle: the [DBusConnection] stays open for the lifetime of the session because
 * closing it tells the portal to drop the screen-cast immediately (the session is bound to the
 * connection that created it). [PortalSession.close] closes the connection.
 */
internal class ScreenCastPortal(
    private val connectionFactory: () -> DBusConnection = ::defaultSessionConnection,
    private val responseTimeout: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
) {

    /**
     * Open a screen-cast session with the portal. Blocks until either the session is established
     * (returning [PortalSession]) or [responseTimeout]ms elapses on any of the three Request
     * round-trips (throwing [PortalTimeoutException]).
     *
     * **First call pops a permission dialog** in the user's compositor. The user picks a screen (or
     * window, depending on [sourceTypes]) and clicks "Share." Subsequent calls within the same
     * login session reuse the granted permission silently. Permission can also be persisted across
     * logins by passing `persistMode = PersistMode.PERSISTENT` (we default to `TRANSIENT` so the
     * permission is scoped to the running JVM — closing and re-opening the VM will re-prompt, which
     * matches how a test or sample run should behave).
     *
     * @param sourceTypes Bitmask of [SourceType] values describing what the dialog should let the
     *   user pick — almost always [SourceType.MONITOR] for our use case.
     * @param cursorMode How the cursor is drawn into the captured stream. [CursorMode.EMBEDDED]
     *   bakes the cursor into the frames (matches what `RecordingOptions.captureCursor=true`
     *   produces on the other backends).
     * @param persistMode See [PersistMode] — defaults to `TRANSIENT` for ephemeral grants.
     */
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun openSession(
        sourceTypes: Set<SourceType> = setOf(SourceType.MONITOR),
        cursorMode: CursorMode = CursorMode.EMBEDDED,
        persistMode: PersistMode = PersistMode.TRANSIENT,
    ): PortalSession {
        val connection = connectionFactory()
        try {
            val sender = senderToken(connection)
            val portal =
                connection.getRemoteObject(
                    PORTAL_BUS_NAME,
                    PORTAL_OBJECT_PATH,
                    ScreenCast::class.java,
                )

            // 1. CreateSession — establish the portal session handle.
            val createToken = uniqueToken("create")
            val createResponse =
                callAndAwaitResponse(
                    connection = connection,
                    sender = sender,
                    handleToken = createToken,
                ) {
                    portal.CreateSession(
                        mapOf(
                            "handle_token" to Variant(createToken),
                            "session_handle_token" to Variant(uniqueToken("session")),
                        )
                    )
                }
            val sessionHandle =
                (createResponse.results["session_handle"]?.value as? String)?.let { DBusPath(it) }
                    ?: throw PortalProtocolException(
                        "CreateSession Response did not include session_handle: " +
                            "${createResponse.results}"
                    )

            // 2. SelectSources — tell the portal we want screens, what cursor mode, etc. The
            // portal validates the options here; the user dialog is fired by Start, not here.
            val selectToken = uniqueToken("select")
            callAndAwaitResponse(
                    connection = connection,
                    sender = sender,
                    handleToken = selectToken,
                ) {
                    portal.SelectSources(
                        sessionHandle,
                        mapOf(
                            "handle_token" to Variant(selectToken),
                            "types" to Variant(UInt32(sourceTypes.toBitmask().toLong())),
                            "multiple" to Variant(false),
                            "cursor_mode" to Variant(UInt32(cursorMode.flag.toLong())),
                            "persist_mode" to Variant(UInt32(persistMode.flag.toLong())),
                        ),
                    )
                }
                .also {
                    // SelectSources's Response carries no payload; just confirm the response code
                    // is OK (0). Non-zero means the user cancelled the dialog OR the portal
                    // rejected the options (e.g. we asked for a source type the compositor
                    // doesn't expose).
                    require(it.code == 0) {
                        "SelectSources rejected (response code ${it.code}). Sources requested: " +
                            "${sourceTypes.toBitmask()}; portal might lack support for those."
                    }
                }

            // 3. Start — fires the user-facing "Share your screen" dialog (or silent on a
            // pre-granted persistent session). The Response carries the streams we want.
            val startToken = uniqueToken("start")
            val startResponse =
                callAndAwaitResponse(
                    connection = connection,
                    sender = sender,
                    handleToken = startToken,
                ) {
                    // parent_window is empty: we don't have a Wayland xdg_toplevel handle to
                    // anchor the dialog to. The portal positions it itself.
                    portal.Start(sessionHandle, "", mapOf("handle_token" to Variant(startToken)))
                }
            require(startResponse.code == 0) {
                "Start was rejected (response code ${startResponse.code}). User likely cancelled " +
                    "the screen-cast permission dialog."
            }

            val streams = parseStreams(startResponse.results)
            require(streams.isNotEmpty()) {
                "Start Response did not include a stream — portal returned no node id. Results: " +
                    "${startResponse.results}"
            }
            val primaryStream = streams.first()
            return PortalSession(
                nodeId = primaryStream.nodeId,
                position = primaryStream.position,
                size = primaryStream.size,
                sessionHandle = sessionHandle.path,
                connection = connection,
            )
        } catch (t: Throwable) {
            // Connection MUST be closed if we throw — otherwise we leak a D-Bus connection AND
            // (if we got past CreateSession) a portal session that the compositor will clean up
            // eventually but at the cost of a confusing "Spectre is sharing your screen"
            // notification lingering after our test crashed.
            runCatching { connection.close() }
            throw t
        }
    }

    /**
     * Make a portal call inside [methodCall] (which must invoke the portal proxy and return the
     * Request object path) and wait synchronously for the Response signal that carries the actual
     * result. Subscribes BEFORE the call to avoid the race where Response could be delivered
     * between method return and handler registration.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun callAndAwaitResponse(
        connection: DBusConnection,
        sender: String,
        handleToken: String,
        methodCall: () -> DBusPath,
    ): PortalResponse {
        val expectedRequestPath = "$PORTAL_REQUEST_PATH_BASE/$sender/$handleToken"
        val signalQueue = LinkedBlockingQueue<PortalResponse>()
        val handler =
            DBusSigHandler<Request.Response> { signal ->
                if (signal.path != expectedRequestPath) return@DBusSigHandler
                signalQueue.offer(
                    PortalResponse(code = signal.response.toInt(), results = signal.results)
                )
            }
        connection.addSigHandler(Request.Response::class.java, handler)
        try {
            val returnedPath = methodCall().path
            // The portal MUST return a path that matches what we predicted from the
            // handle_token. If it doesn't, we'd silently wait for a signal that never matches
            // — fail fast instead so a portal-side bug is debuggable.
            require(returnedPath == expectedRequestPath) {
                "Portal returned a Request path ($returnedPath) that does not match the path " +
                    "computed from sender=$sender / handle_token=$handleToken " +
                    "($expectedRequestPath). The handle_token contract is broken."
            }
            return signalQueue.poll(responseTimeout, TimeUnit.MILLISECONDS)
                ?: throw PortalTimeoutException(
                    "Timed out after ${responseTimeout}ms waiting for portal Response signal " +
                        "on $expectedRequestPath. Common causes: user dismissed the dialog " +
                        "without responding; portal service crashed; D-Bus message bus stalled."
                )
        } finally {
            // Always remove the handler. Leaking handlers across many openSession calls would
            // turn into a slow memory creep + firing extra closures on every Response.
            runCatching { connection.removeSigHandler(Request.Response::class.java, handler) }
        }
    }

    /**
     * Compute the sender token used in the Request object path. dbus-java exposes the connection's
     * unique bus name in `:X.Y` form; the portal computes the path from that name with `:` and `.`
     * replaced by `_`.
     */
    private fun senderToken(connection: DBusConnection): String =
        connection.uniqueName.removePrefix(":").replace('.', '_')

    /**
     * Each portal call needs a unique handle_token within our connection so we can predict its
     * Request path without colliding with other calls on the same connection. Using a sequence
     * number per ScreenCastPortal instance plus a coarse-grained category prefix gives us readable
     * values (`spectre_create_1`, `spectre_select_2`, etc.) without UUID overhead.
     */
    private fun uniqueToken(category: String): String =
        "spectre_${category}_${tokenCounter.incrementAndGet()}"

    private val tokenCounter = AtomicInteger(0)

    private companion object {
        const val PORTAL_BUS_NAME = "org.freedesktop.portal.Desktop"
        const val PORTAL_OBJECT_PATH = "/org/freedesktop/portal/desktop"
        const val PORTAL_REQUEST_PATH_BASE = "/org/freedesktop/portal/desktop/request"
        const val DEFAULT_RESPONSE_TIMEOUT_MS: Long = 60_000

        fun defaultSessionConnection(): DBusConnection {
            // Two-step discovery: prefer DBUS_SESSION_BUS_ADDRESS if set (matches what the
            // session shell / systemd-logind exports), otherwise fall back to dbus-java's
            // built-in `forSessionBus()` which queries `/org/freedesktop/DBus` and the
            // standard discovery paths. Spelling this out fixes a footgun where SSH-spawned
            // processes inherit DBUS_SESSION_BUS_ADDRESS but dbus-java's automatic discovery
            // can blow past it and hit the transport-error path.
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")?.takeIf { it.isNotBlank() }
            return if (address != null) {
                DBusConnectionBuilder.forAddress(address).build()
            } else {
                DBusConnectionBuilder.forSessionBus().build()
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun parseStreams(results: Map<String, Variant<*>>): List<PortalStream> {
            // The "streams" key is `a(ua{sv})` — array of (uint32 node_id, vardict properties).
            // dbus-java surfaces this as List<List<Any>> where each inner list is [UInt32,
            // Map<String, Variant<*>>]. We pull node_id, position, and size out of each.
            val raw = results["streams"]?.value as? List<*> ?: return emptyList()
            return raw.mapNotNull { entry ->
                val list = entry as? List<*> ?: return@mapNotNull null
                if (list.size < 2) return@mapNotNull null
                val nodeId = (list[0] as? UInt32)?.toInt() ?: return@mapNotNull null
                val props = list[1] as? Map<String, Variant<*>> ?: emptyMap()
                val pos = (props["position"]?.value as? List<*>)?.let { Pair(it) }
                val size = (props["size"]?.value as? List<*>)?.let { Pair(it) }
                PortalStream(nodeId = nodeId, position = pos ?: (0 to 0), size = size ?: (0 to 0))
            }
        }

        @Suppress("FunctionName", "UNUSED_PARAMETER")
        fun Pair(list: List<*>): Pair<Int, Int> {
            // Position / size come back as `(ii)` D-Bus structs of two int32s.
            val first = (list.getOrNull(0) as? Number)?.toInt() ?: 0
            val second = (list.getOrNull(1) as? Number)?.toInt() ?: 0
            return first to second
        }
    }

    /**
     * `org.freedesktop.portal.ScreenCast` D-Bus interface. dbus-java introspects the annotated
     * interface and routes method calls to the right D-Bus method names.
     */
    // dbus-java introspects method names case-sensitively against the D-Bus member names,
    // so these MUST be PascalCase even though Kotlin's convention says camelCase. Suppressing
    // FunctionNaming on the interface block as a whole.
    //
    // `@JvmSuppressWildcards` is critical: without it, Kotlin compiles `Map<String, Variant<*>>`
    // to Java as `Map<String, ? extends Variant<?>>`, and dbus-java's reflective signature
    // derivation chokes on the wildcard and emits the body signature as `a{s}` instead of
    // `a{sv}`. The session bus then rejects the malformed message and slams the connection
    // ("Underlying transport returned -1" with a hard EOF). With the annotation, Kotlin emits
    // `Map<String, Variant<?>>` as the Java type, which dbus-java introspects correctly.
    @DBusInterfaceName("org.freedesktop.portal.ScreenCast")
    @Suppress("FunctionNaming")
    @JvmSuppressWildcards
    internal interface ScreenCast : DBusInterface {

        fun CreateSession(options: Map<String, Variant<*>>): DBusPath

        fun SelectSources(sessionHandle: DBusPath, options: Map<String, Variant<*>>): DBusPath

        fun Start(
            sessionHandle: DBusPath,
            parentWindow: String,
            options: Map<String, Variant<*>>,
        ): DBusPath
    }

    /**
     * `org.freedesktop.portal.Request` only contains the `Response` signal that we care about.
     * dbus-java DBusSignal subclasses follow a strict shape: the signal's positional arguments
     * arrive as constructor parameters in the order the bus delivers them.
     */
    @DBusInterfaceName("org.freedesktop.portal.Request")
    internal interface Request : DBusInterface {

        @DBusInterfaceName("org.freedesktop.portal.Request")
        class Response(
            objectPath: String,
            val response: UInt32,
            val results: Map<String, Variant<*>>,
        ) : DBusSignal(objectPath, response, results)
    }

    /** Internal tuple used while parsing Start.Response — promoted to PortalSession on success. */
    private data class PortalStream(
        val nodeId: Int,
        val position: Pair<Int, Int>,
        val size: Pair<Int, Int>,
    )

    private data class PortalResponse(val code: Int, val results: Map<String, Variant<*>>)
}

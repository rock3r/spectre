package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.defaultLinuxSessionEnvLookup
import dev.sebastiano.spectre.recording.waylandHelperEnvironment
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlinx.serialization.json.Json

/**
 * Client for the long-lived `spectre-wayland-helper --session` process.
 *
 * One helper owns the RemoteDesktop portal session for the user seat. Parallel JVMs connect to the
 * same unix socket instead of each opening `java.awt.Robot`'s portal.
 *
 * [envLookup] should include the runtime-dir and D-Bus fallbacks of
 * [dev.sebastiano.spectre.recording.linuxSessionEnvLookup]: it picks the session directory and the
 * variables passed to the helper process.
 */
internal class WaylandSessionClient
internal constructor(
    helperExtractor: WaylandHelperBinaryExtractor,
    private val envLookup: (String) -> String?,
    processFactory: SessionProcessFactory,
    waitForSocket: (Path, Long) -> Boolean = ::waitUntilExists,
    private val consentPrompt: () -> WaylandConsentPrompt = {
        waylandConsentPrompt(System::getProperty, envLookup)
    },
) {
    constructor() :
        this(
            helperExtractor = DefaultWaylandHelperBinaryExtractor.instance,
            envLookup = defaultLinuxSessionEnvLookup,
            processFactory = SystemSessionProcessFactory,
        )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val inputConnection = WaylandSessionInputConnection(json)
    private val consentMemo = WaylandSessionConsentMemo()

    private val starter =
        WaylandSessionStarter(helperExtractor, envLookup, processFactory, waitForSocket)

    fun mouseMove(x: Int, y: Int) {
        send(Command.PointerMove(x, y))
    }

    fun mousePress(buttons: Int) {
        send(Command.PointerButton(button = buttons, pressed = true))
    }

    fun mouseRelease(buttons: Int) {
        send(Command.PointerButton(button = buttons, pressed = false))
    }

    fun keyPress(keyCode: Int) {
        send(Command.Key(keyCode = keyCode, pressed = true))
    }

    fun keyRelease(keyCode: Int) {
        send(Command.Key(keyCode = keyCode, pressed = false))
    }

    fun mouseWheel(wheelClicks: Int) {
        send(Command.PointerAxis(axis = VERTICAL_POINTER_AXIS, steps = wheelClicks))
    }

    fun screenshot(region: Rectangle): BufferedImage {
        val output = Files.createTempFile("spectre-wayland-session-", ".png")
        try {
            send(
                Command.Screenshot(
                    backend = CaptureBackend.WAYLAND_PORTAL,
                    target = CaptureTarget.REGION,
                    sourceTypes = listOf(SourceType.MONITOR),
                    cursorMode = CursorMode.HIDDEN,
                    region = Region(region.x, region.y, region.width, region.height),
                    screenSize = awtDisplayBoundsContaining(region)?.toWire(),
                    output = output.toAbsolutePath().toString(),
                )
            )
            return ImageIO.read(output.toFile())
                ?: error("Wayland session helper did not produce a readable PNG at $output")
        } finally {
            output.deleteIfExists()
        }
    }

    fun send(command: Command): Event =
        synchronized(lock) { inputConnection.send(ensureSocket(), command) }

    /** The session socket path for this environment, or null without a runtime directory. */
    fun sessionSocketPath(): Path? = sessionDirOrNull()?.let { waylandSessionPaths(it).socket }

    /**
     * Starts the session helper for an attended `spectre permissions request`, or joins the running
     * one. The helper may open the Share dialog and waits [consentTimeoutSeconds] for each portal
     * step. Unlike captures, this ignores the consent memo and [WaylandConsentPrompt.NEVER]: a
     * person asked for the dialog.
     */
    fun openSessionForRequest(consentTimeoutSeconds: Long): Path =
        synchronized(lock) {
            starter.startOrJoin(
                waylandSessionPaths(sessionDir()),
                SessionStart(WaylandConsentPrompt.ALLOW, consentTimeoutSeconds),
            )
        }

    fun startHeld(command: Command): HeldRecordingSession {
        val socket = synchronized(lock) { ensureSocket() }
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        var transferred = false
        try {
            val event = connectAndExchange(channel, socket, command)
            check(event is Event.Started) {
                "spectre-wayland-helper session did not start recording: $event"
            }
            transferred = true
            return HeldRecordingSession(channel, json)
        } finally {
            if (!transferred) {
                channel.close()
            }
        }
    }

    private fun connectAndExchange(channel: SocketChannel, socket: Path, command: Command): Event {
        channel.connect(UnixDomainSocketAddress.of(socket))
        return exchangeSessionCommand(channel, json, command)
    }

    private fun ensureSocket(): Path {
        val paths = waylandSessionPaths(sessionDir())
        return consentMemo.resolve(paths.socket, ::probeUnixSocket) {
            starter.startOrJoin(paths, SessionStart(consentPrompt()))
        }
    }

    private fun sessionDirOrNull(): Path? {
        envLookup("SPECTRE_WAYLAND_SESSION_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return Path.of(it)
            }
        return envLookup("XDG_RUNTIME_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it).resolve("spectre") }
    }

    private fun sessionDir(): Path {
        sessionDirOrNull()?.let {
            return it
        }
        error(
            "The Wayland session needs a private runtime directory, but XDG_RUNTIME_DIR is not " +
                "set and /run/user/<uid> is missing or not owned by this user. Set " +
                "XDG_RUNTIME_DIR to the seat user's runtime directory (normally /run/user/<uid>), " +
                "or set SPECTRE_WAYLAND_SESSION_DIR to a private directory for the Spectre session " +
                "socket."
        )
    }

    internal fun interface SessionProcessFactory {
        /** Starts the helper with [extraEnvironment] added to this JVM's environment. */
        fun startSession(helperPath: Path, extraEnvironment: Map<String, String>): Process
    }

    internal object SystemSessionProcessFactory : SessionProcessFactory {
        override fun startSession(
            helperPath: Path,
            extraEnvironment: Map<String, String>,
        ): Process =
            ProcessBuilder(helperPath.toString(), "--session")
                .apply { environment().putAll(extraEnvironment) }
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
    }

    /** How one helper start may prompt, and how long the JVM waits for its socket. */
    internal data class SessionStart(
        val prompt: WaylandConsentPrompt,
        val consentTimeoutSeconds: Long? = null,
    ) {
        val extraEnvironment: Map<String, String>
            get() = buildMap {
                if (prompt == WaylandConsentPrompt.NEVER) {
                    put(WAYLAND_CONSENT_PROMPT_ENV, WaylandConsentPrompt.NEVER_VALUE)
                }
                consentTimeoutSeconds?.let { put(WAYLAND_CONSENT_TIMEOUT_ENV, it.toString()) }
            }

        /** The helper waits for up to [PORTAL_CONSENT_STEPS] portal responses in a row. */
        val socketTimeoutMs: Long
            get() =
                consentTimeoutSeconds?.let {
                    TimeUnit.SECONDS.toMillis(it * PORTAL_CONSENT_STEPS + SOCKET_MARGIN_SECONDS)
                } ?: SESSION_SOCKET_TIMEOUT_MS
    }

    private companion object {
        const val SESSION_SOCKET_TIMEOUT_MS: Long = 90_000
        const val SOCKET_POLL_MS: Long = 50
        const val PORTAL_CONSENT_STEPS: Long = 3
        const val SOCKET_MARGIN_SECONDS: Long = 30

        fun waitUntilExists(path: Path, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                Thread.sleep(SOCKET_POLL_MS)
                if (Files.exists(path)) return true
            }
            return Files.exists(path)
        }
    }
}

/**
 * Starts the session helper, or joins one that is starting. Not thread-safe: [WaylandSessionClient]
 * calls it under its own lock.
 */
internal class WaylandSessionStarter(
    private val helperExtractor: WaylandHelperBinaryExtractor,
    private val envLookup: (String) -> String?,
    private val processFactory: WaylandSessionClient.SessionProcessFactory,
    private val waitForSocket: (Path, Long) -> Boolean,
) {
    /**
     * The helper started here that has not bound its socket yet, usually because its Share dialog
     * is still open. A capture whose own time budget runs out (a 5 s visual-idle sample, for
     * example) is interrupted while it waits, but the helper and its dialog stay up: the next
     * capture waits on this helper instead of starting a new one, and reads its exit status when
     * the dialog times out or is denied.
     */
    private var pendingHelper: Process? = null

    fun startOrJoin(paths: WaylandSessionPaths, start: WaylandSessionClient.SessionStart): Path {
        if (Files.exists(paths.socket) && probeUnixSocket(paths.socket)) {
            pendingHelper = null
            return paths.socket
        }
        val helper = pendingHelper?.takeIf { it.isAlive } ?: startHelper(start)
        try {
            val socket =
                resolveWaylandSessionSocket(
                    paths = paths,
                    socketIsLive = ::probeUnixSocket,
                    startHelper = {},
                    waitForSocket = waitForSocket,
                    timeoutMs = start.socketTimeoutMs,
                    helperExitCode = { helper.sessionExitCode() },
                )
            pendingHelper = null
            return socket
        } catch (error: WaylandConsentNotGrantedException) {
            pendingHelper = null
            if (start.prompt == WaylandConsentPrompt.NEVER) {
                throw WaylandConsentNotGrantedException(
                    waylandNoStoredGrantMessage(waylandRemoteDesktopTokenPath(envLookup)),
                    error,
                )
            }
            throw error
        } catch (error: IllegalStateException) {
            // A helper that is still waiting on its dialog stays pending for the next capture.
            if (helper.sessionExitCode() != null) pendingHelper = null
            throw error
        }
    }

    /**
     * Starts a helper. A pending helper that exited while nobody waited reports its result first: a
     * dialog that timed out or was denied becomes [WaylandConsentNotGrantedException], which the
     * consent memo then keeps.
     */
    private fun startHelper(start: WaylandSessionClient.SessionStart): Process {
        pendingHelper?.let { finished ->
            pendingHelper = null
            failIfHelperExitedFatally(finished.sessionExitCode())
        }
        if (start.prompt == WaylandConsentPrompt.NEVER) {
            val token = waylandRemoteDesktopTokenPath(envLookup)
            if (token == null || !hasStoredRestoreToken(token)) {
                throw WaylandConsentNotGrantedException(waylandNoStoredGrantMessage(token))
            }
        }
        val helper = helperExtractor.extract()
        val process =
            processFactory.startSession(
                helper,
                waylandHelperEnvironment(envLookup) + start.extraEnvironment,
            )
        pendingHelper = process
        return process
    }
}

/**
 * Remembers, per session socket, that the helper could not get portal consent (#634).
 *
 * Without it, every capture started a new helper and a new 60 s consent dialog after the first one
 * timed out. The memo lives as long as its [WaylandSessionClient]; the default client is a JVM
 * singleton, so a failed consent is replayed for the rest of the JVM. It is keyed by socket path,
 * so a different session directory still gets its own attempt. A live socket always wins: when
 * another process brings the session up, the memo is cleared and the socket is used. Only
 * [WaylandConsentNotGrantedException] is remembered; a crashed or missing helper is retried.
 *
 * Not thread-safe: [WaylandSessionClient] calls it under its own lock.
 */
internal class WaylandSessionConsentMemo {
    private val failures = mutableMapOf<Path, WaylandConsentNotGrantedException>()

    fun resolve(socket: Path, socketIsLive: (Path) -> Boolean, start: () -> Path): Path {
        val failure = failures[socket]
        if (failure != null) {
            if (Files.exists(socket) && socketIsLive(socket)) {
                failures.remove(socket)
                return socket
            }
            throw WaylandConsentNotGrantedException(failure.message.orEmpty(), failure)
        }
        try {
            return start()
        } catch (error: WaylandConsentNotGrantedException) {
            failures[socket] = error
            throw error
        }
    }
}

/** Reuses one unix connection so a mid-gesture disconnect can release that client's held input. */
internal class WaylandSessionInputConnection(private val json: Json) {
    private var channel: SocketChannel? = null

    fun send(socket: Path, command: Command): Event {
        val open = openOrReuse(socket)
        try {
            return exchangeSessionCommand(open, json, command)
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    fun close() {
        try {
            channel?.close()
        } finally {
            channel = null
        }
    }

    private fun openOrReuse(socket: Path): SocketChannel {
        channel
            ?.takeIf { it.isOpen && it.isConnected }
            ?.let {
                return it
            }
        close()
        val opened = SocketChannel.open(StandardProtocolFamily.UNIX)
        opened.connect(UnixDomainSocketAddress.of(socket))
        channel = opened
        return opened
    }
}

/** True when [socket] accepts a unix-domain connection now. */
internal fun probeUnixSocket(socket: Path): Boolean =
    try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket))
            true
        }
    } catch (_: IOException) {
        false
    }

private fun exchangeSessionCommand(channel: SocketChannel, json: Json, command: Command): Event {
    writeHeldLine(channel, json.encodeToString(Command.serializer(), command))
    val line = readHeldLine(channel)
    val event = json.decodeFromString(Event.serializer(), line)
    if (event is Event.Error) {
        throw WaylandHelperErrorException(event.kind, event.message)
    }
    return event
}

internal class HeldRecordingSession(private val channel: SocketChannel, private val json: Json) :
    AutoCloseable {
    fun stop(): Event {
        writeHeldLine(channel, json.encodeToString(Command.serializer(), Command.Stop))
        val line = readHeldLine(channel)
        val event = json.decodeFromString(Event.serializer(), line)
        if (event is Event.Error) {
            throw WaylandHelperErrorException(event.kind, event.message)
        }
        return event
    }

    override fun close() {
        channel.close()
    }
}

private fun writeHeldLine(channel: SocketChannel, line: String) {
    val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
        channel.write(buffer)
    }
}

private fun readHeldLine(channel: SocketChannel): String {
    val builder = StringBuilder()
    val one = ByteBuffer.allocate(1)
    while (true) {
        one.clear()
        val n = channel.read(one)
        check(n >= 0) { "Wayland session helper closed the socket before a reply" }
        val ch = one.get(0).toInt().toChar()
        if (ch == '\n') break
        if (ch != '\r') builder.append(ch)
    }
    return builder.toString()
}

internal object DefaultWaylandSessionClient {
    val instance: WaylandSessionClient by lazy { WaylandSessionClient() }
}

/**
 * Reflection target for core's Wayland [dev.sebastiano.spectre.core.RobotAdapter] without linking
 * recording into core.
 */
internal object WaylandOsBridge {
    private val client: WaylandSessionClient
        get() = DefaultWaylandSessionClient.instance

    @JvmStatic
    @JvmName("mouseMove")
    fun mouseMove(x: Int, y: Int) {
        client.mouseMove(x, y)
    }

    @JvmStatic
    @JvmName("mousePress")
    fun mousePress(buttons: Int) {
        client.mousePress(buttons)
    }

    @JvmStatic
    @JvmName("mouseRelease")
    fun mouseRelease(buttons: Int) {
        client.mouseRelease(buttons)
    }

    @JvmStatic
    @JvmName("keyPress")
    fun keyPress(keyCode: Int) {
        client.keyPress(keyCode)
    }

    @JvmStatic
    @JvmName("keyRelease")
    fun keyRelease(keyCode: Int) {
        client.keyRelease(keyCode)
    }

    @JvmStatic
    @JvmName("mouseWheel")
    fun mouseWheel(wheelClicks: Int) {
        client.mouseWheel(wheelClicks)
    }

    @JvmStatic
    @JvmName("createScreenCapture")
    fun createScreenCapture(region: Rectangle): BufferedImage = client.screenshot(region)
}

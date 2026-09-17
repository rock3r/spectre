package dev.sebastiano.spectre.recording.portal

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
 */
internal class WaylandSessionClient
internal constructor(
    private val helperExtractor: WaylandHelperBinaryExtractor,
    private val envLookup: (String) -> String?,
    private val processFactory: SessionProcessFactory,
    private val waitForSocket: (Path, Long) -> Boolean = ::waitUntilExists,
) {
    constructor() :
        this(
            helperExtractor = DefaultWaylandHelperBinaryExtractor.instance,
            envLookup = System::getenv,
            processFactory = SystemSessionProcessFactory,
        )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

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
        synchronized(lock) {
            val socket = ensureSocket()
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                connectAndExchange(channel, socket, command)
            }
        }

    fun startHeld(command: Command.Start): HeldRecordingSession {
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
        writeLine(channel, json.encodeToString(Command.serializer(), command))
        val line = readLine(channel)
        val event = json.decodeFromString(Event.serializer(), line)
        if (event is Event.Error) {
            error(
                "spectre-wayland-helper session error: kind=${event.kind} message=${event.message}"
            )
        }
        return event
    }

    private fun ensureSocket(): Path {
        val paths = waylandSessionPaths(sessionDir())
        return resolveWaylandSessionSocket(
            paths = paths,
            socketIsLive = ::probeUnixSocket,
            startHelper = {
                val helper = helperExtractor.extract()
                processFactory.startSession(helper)
            },
            waitForSocket = waitForSocket,
            timeoutMs = SESSION_SOCKET_TIMEOUT_MS,
        )
    }

    private fun probeUnixSocket(socket: Path): Boolean =
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socket))
                true
            }
        } catch (_: IOException) {
            false
        }

    private fun sessionDir(): Path {
        envLookup("SPECTRE_WAYLAND_SESSION_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return Path.of(it)
            }
        envLookup("XDG_RUNTIME_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return Path.of(it).resolve("spectre")
            }
        error("XDG_RUNTIME_DIR or SPECTRE_WAYLAND_SESSION_DIR is required for the Wayland session")
    }

    private fun writeLine(channel: SocketChannel, line: String) {
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun readLine(channel: SocketChannel): String {
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

    internal fun interface SessionProcessFactory {
        fun startSession(helperPath: Path): Process
    }

    internal object SystemSessionProcessFactory : SessionProcessFactory {
        override fun startSession(helperPath: Path): Process =
            ProcessBuilder(helperPath.toString(), "--session")
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
    }

    private companion object {
        const val SESSION_SOCKET_TIMEOUT_MS: Long = 90_000
        const val SOCKET_POLL_MS: Long = 50

        fun waitUntilExists(path: Path, timeoutMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (Files.exists(path)) return true
                Thread.sleep(SOCKET_POLL_MS)
            }
            return Files.exists(path)
        }
    }
}

internal class HeldRecordingSession(private val channel: SocketChannel, private val json: Json) :
    AutoCloseable {
    fun stop(): Event {
        writeHeldLine(channel, json.encodeToString(Command.serializer(), Command.Stop))
        val line = readHeldLine(channel)
        val event = json.decodeFromString(Event.serializer(), line)
        if (event is Event.Error) {
            error(
                "spectre-wayland-helper session error: kind=${event.kind} message=${event.message}"
            )
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

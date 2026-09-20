package dev.sebastiano.spectre.core

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
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists

/**
 * Talks to an already-running `spectre-wayland-helper --session` over the seat unix socket.
 *
 * Injected targets do not ship `spectre-recording`. They still must not open `java.awt.Robot`'s
 * portal; they reuse the helper the test JVM (or `SPECTRE_WAYLAND_HELPER`) already started.
 */
internal class WaylandSeatSocketAdapter(
    private val socketPath: () -> Path? = ::liveWaylandSessionSocket
) : RobotAdapter {
    override val autoDelayMs: Int = 0
    override val requiresOffEdt: Boolean = true
    override val deliversRealOsInput: Boolean = true
    override val needsSelectAllLineFallback: Boolean = true
    override val shouldDrainAfterClipboardPaste: Boolean
        get() = true

    override fun getLockingKeyState(keyCode: Int): Boolean? = awtLockingKeyState(keyCode)

    override fun mouseMove(x: Int, y: Int) {
        send("""{"command":"pointer_move","x":$x,"y":$y}""")
    }

    override fun mousePress(buttons: Int) {
        send("""{"command":"pointer_button","button":$buttons,"pressed":true}""")
    }

    override fun mouseRelease(buttons: Int) {
        send("""{"command":"pointer_button","button":$buttons,"pressed":false}""")
    }

    override fun keyPress(keyCode: Int) {
        send("""{"command":"key","key_code":$keyCode,"pressed":true}""")
    }

    override fun keyRelease(keyCode: Int) {
        send("""{"command":"key","key_code":$keyCode,"pressed":false}""")
    }

    override fun mouseWheel(wheelClicks: Int) {
        send(
            """{"command":"pointer_axis","axis":$VERTICAL_SEAT_POINTER_AXIS,"steps":$wheelClicks}"""
        )
    }

    override fun waitForIdle() = Unit

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        screenshotToLogicalSize(captureRaw(region), region)

    override fun createDeviceScaleScreenCapture(region: Rectangle): BufferedImage =
        captureRaw(region)

    private fun captureRaw(region: Rectangle): BufferedImage {
        val output = Files.createTempFile("spectre-wayland-seat-", ".png")
        try {
            val outputPath =
                output.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"")
            send(
                waylandScreenshotCommandJson(
                    region = region,
                    outputPath = outputPath,
                    screenSize = awtDisplayBoundsContaining(region),
                )
            )
            return ImageIO.read(output.toFile())
                ?: error("Wayland session helper did not produce a readable PNG at $output")
        } finally {
            output.deleteIfExists()
        }
    }

    private fun send(line: String) {
        val socket = socketPath() ?: startHelperFromEnv() ?: error(MISSING_WAYLAND_HELPER_MESSAGE)
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket))
            writeLine(channel, line)
            val reply = readLine(channel)
            if (reply.contains("\"event\":\"error\"")) {
                error("spectre-wayland-helper session error: $reply")
            }
        }
    }
}

internal const val VERTICAL_SEAT_POINTER_AXIS: Int = 0
internal const val WAYLAND_SESSION_OWNED_EXIT: Int = 75
private const val HELPER_START_TIMEOUT_MS: Long = 90_000
private const val HELPER_START_POLL_MS: Long = 50

internal fun startHelperFromEnv(env: (String) -> String? = System::getenv): Path? {
    val helper = env("SPECTRE_WAYLAND_HELPER")?.takeIf { it.isNotBlank() } ?: return null
    val process =
        ProcessBuilder(helper, "--session")
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
    return awaitInjectedHelperSocket(
        liveSocket = ::liveWaylandSessionSocket,
        helperAlive = { process.isAlive },
        exitDetail = { "exit ${process.exitValue()}" },
        timeoutMs = HELPER_START_TIMEOUT_MS,
        pollMs = HELPER_START_POLL_MS,
        ownershipLost = { !process.isAlive && !isFatalInjectedHelperExit(process.exitValue()) },
    )
}

internal fun isFatalInjectedHelperExit(exitCode: Int): Boolean =
    exitCode != 0 && exitCode != WAYLAND_SESSION_OWNED_EXIT

internal fun awaitInjectedHelperSocket(
    liveSocket: () -> Path?,
    helperAlive: () -> Boolean,
    exitDetail: () -> String,
    timeoutMs: Long,
    pollMs: Long,
    sleep: (Long) -> Unit = { Thread.sleep(it) },
    ownershipLost: () -> Boolean = { false },
): Path? {
    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        liveSocket()?.let {
            return it
        }
        if (!helperAlive() && !ownershipLost()) {
            error(
                "SPECTRE_WAYLAND_HELPER --session exited before binding the seat socket " +
                    "(${exitDetail()})"
            )
        }
        sleep(pollMs)
    }
    return liveSocket()
}

internal fun waylandScreenshotCommandJson(
    region: Rectangle,
    outputPath: String,
    screenSize: Rectangle?,
): String = buildString {
    append("""{"command":"screenshot","backend":"wayland_portal","target":"region",""")
    append(""""source_types":["monitor"],"cursor_mode":"hidden",""")
    append(""""region":{"x":${region.x},"y":${region.y},"width":${region.width},""")
    append(""""height":${region.height}},"output":"$outputPath"""")
    if (screenSize != null) {
        append(
            ""","screen_size":[${screenSize.x},${screenSize.y},${screenSize.width},${screenSize.height}]"""
        )
    }
    append("}")
}

internal fun awtDisplayBoundsContaining(
    region: Rectangle,
    displays: List<Rectangle> = currentAwtDisplayBounds(),
): Rectangle? {
    if (displays.isEmpty()) return null
    val centerX = region.centerX
    val centerY = region.centerY
    return displays.firstOrNull { it.contains(centerX, centerY) }
        ?: displays.firstOrNull { it.intersects(region) }
}

private fun currentAwtDisplayBounds(): List<Rectangle> {
    if (java.awt.GraphicsEnvironment.isHeadless()) return emptyList()
    return java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
        device.defaultConfiguration.bounds
    }
}

internal fun waylandSessionSocketFromEnv(env: (String) -> String? = System::getenv): Path? {
    val override = env("SPECTRE_WAYLAND_SESSION_DIR")?.takeIf { it.isNotBlank() }
    val dir =
        when {
            override != null -> Path.of(override)
            else ->
                env("XDG_RUNTIME_DIR")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Path.of(it).resolve("spectre") }
        } ?: return null
    return dir.resolve("wayland-session.sock")
}

internal fun liveWaylandSessionSocket(): Path? {
    val path = waylandSessionSocketFromEnv() ?: return null
    if (!Files.exists(path)) return null
    return try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(path))
            path
        }
    } catch (_: IOException) {
        null
    }
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

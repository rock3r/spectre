@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.core.capture.cropImageToScreenRegion
import java.awt.Dialog
import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Window
import java.awt.image.BufferedImage
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class ScreenCaptureBackendTest {

    @Test
    fun `optional native bridge is absent without recording on the classpath`() {
        val recordingFreeLoader =
            object : ClassLoader(null) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> =
                    throw ClassNotFoundException(name)
            }

        assertNull(nativeWindowCaptureFor(recordingFreeLoader))
    }

    @Test
    fun `optional native bridge is discovered without a core compile dependency`() {
        assertTrue(nativeWindowCaptureFor(javaClass.classLoader) != null)
    }

    @Test
    fun `begin native capture wait is a no-op without recording on the classpath`() {
        val recordingFreeLoader =
            object : ClassLoader(null) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> =
                    throw ClassNotFoundException(name)
            }

        assertEquals(0L, beginNativePlatformCaptureWait(recordingFreeLoader))
        endNativePlatformCaptureWait(recordingFreeLoader, waitId = 1L)
    }

    @Test
    fun `overlapping native capture waits receive distinct ids`() {
        val first = beginNativePlatformCaptureWait(javaClass.classLoader)
        val second = beginNativePlatformCaptureWait(javaClass.classLoader)
        try {
            assertTrue(first != 0L)
            assertTrue(second != 0L)
            assertTrue(first != second, "overlapping waits must not share one probe slot")
        } finally {
            endNativePlatformCaptureWait(javaClass.classLoader, first)
            endNativePlatformCaptureWait(javaClass.classLoader, second)
        }
    }

    @Test
    fun `native usability probe is absent without recording on the classpath`() {
        val recordingFreeLoader =
            object : ClassLoader(null) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> =
                    throw ClassNotFoundException(name)
            }

        assertFalse(isNativePlatformCaptureUsable(recordingFreeLoader))
    }

    @Test
    fun `native usability probe loads when recording is present`() {
        // Value is host-dependent (Linux needs gst-launch-1.0). The contract is that reflection
        // reaches the recording bridge instead of throwing.
        isNativePlatformCaptureUsable(javaClass.classLoader)
    }

    @Test
    fun `headless driver disables platform capture routing`() {
        assertFalse(RobotDriver.headless().allowsPlatformCapture)
    }

    @Test
    fun `native client images crop in their client-window coordinate space`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame().apply {
                setBounds(20, 30, 300, 200)
                addNotify()
            }
        val clientBounds = Rectangle(20, 54, 300, 176)
        val image = BufferedImage(300, 176, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(10, 10, 0xFF112233.toInt())
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("should not fall back") },
                nativeCapture = { image },
                nativeCaptureBounds = { _, _, _, _ -> clientBounds },
            )
        try {
            val capture = backend.captureWindow(tracked(frame), Rectangle(frame.bounds))
            val crop =
                cropImageToScreenRegion(
                    capture.image,
                    Rectangle(30, 64, 1, 1),
                    capture.boundsOnScreen,
                )
            assertEquals(0xFF112233.toInt(), crop.getRGB(0, 0))
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `scaled Linux native client images retain client-window coordinates`() {
        val windowBounds = Rectangle(20, 30, 300, 200)
        val clientBounds =
            nativeWindowCaptureBounds(
                osName = "Linux",
                windowBounds = windowBounds,
                insets = Insets(24, 0, 0, 0),
                isWayland = false,
            )
        val image = BufferedImage(600, 352, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(20, 20, 0xFF112233.toInt())

        val crop = cropImageToScreenRegion(image, Rectangle(30, 64, 1, 1), clientBounds)

        assertEquals(Rectangle(20, 54, 300, 176), clientBounds)
        assertEquals(0xFF112233.toInt(), crop.getRGB(0, 0))
    }

    @Test
    fun `Wayland native images retain full-frame coordinates`() {
        val windowBounds = Rectangle(20, 30, 300, 200)

        assertEquals(
            windowBounds,
            nativeWindowCaptureBounds(
                osName = "Linux",
                windowBounds = windowBounds,
                insets = Insets(24, 0, 0, 0),
                isWayland = true,
            ),
        )
    }

    @Test
    fun `Wayland detection recognizes a runtime-directory socket without DISPLAY`() {
        // SSH into a Wayland host: no DISPLAY, residual compositor socket → Wayland.
        assertTrue(
            isWaylandSession(
                getenv = { if (it == "XDG_RUNTIME_DIR") "/run/user/1000" else null },
                runtimeDirHasWaylandSocket = { it == Path.of("/run/user/1000") },
            )
        )
    }

    @Test
    fun `Wayland detection ignores residual socket when Xvfb DISPLAY is set`() {
        // #397: Xvfb + leftover wayland-* under XDG_RUNTIME_DIR must not route to portal.
        assertFalse(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":99"
                        "XDG_RUNTIME_DIR" -> "/run/user/1000"
                        else -> null
                    }
                },
                runtimeDirHasWaylandSocket = { true },
                displayIsPureX11 = { false },
            )
        )
    }

    @Test
    fun `Wayland detection prefers pure X11 DISPLAY over inherited Wayland env`() {
        assertFalse(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":99"
                        "XDG_SESSION_TYPE" -> "wayland"
                        "WAYLAND_DISPLAY" -> "wayland-0"
                        "XDG_RUNTIME_DIR" -> "/run/user/1000"
                        else -> null
                    }
                },
                runtimeDirHasWaylandSocket = { true },
                displayIsPureX11 = { it == ":99" },
            )
        )
    }

    @Test
    fun `Wayland detection keeps portal routing for XWayland DISPLAY`() {
        assertTrue(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":0"
                        "XDG_SESSION_TYPE" -> "wayland"
                        "WAYLAND_DISPLAY" -> "wayland-0"
                        else -> null
                    }
                },
                runtimeDirHasWaylandSocket = { true },
                displayIsPureX11 = { false },
                displayIsXwayland = { it == ":0" },
            )
        )
    }

    @Test
    fun `Wayland detection honors SPECTRE_CAPTURE_BACKEND override`() {
        assertFalse(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "SPECTRE_CAPTURE_BACKEND" -> "x11"
                        "XDG_SESSION_TYPE" -> "wayland"
                        "WAYLAND_DISPLAY" -> "wayland-0"
                        else -> null
                    }
                }
            )
        )
        assertTrue(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "SPECTRE_CAPTURE_BACKEND" -> "wayland"
                        "DISPLAY" -> ":99"
                        else -> null
                    }
                },
                displayIsPureX11 = { true },
            )
        )
    }

    @Test
    fun `Wayland detection ignores an unreadable runtime directory`() {
        assertFalse(
            isWaylandSession(
                getenv = { if (it == "XDG_RUNTIME_DIR") "/run/user/1000" else null },
                runtimeDirHasWaylandSocket = { error("unreadable runtime directory") },
            )
        )
    }

    @Test
    fun `Wayland detection prefers DISPLAY when the inherited Wayland socket is missing`() {
        // #575: X11 container or Xvfb under a shell that inherited the host's Wayland env, with
        // no Xvfb process visible and no xdpyinfo. The compositor socket does not exist here.
        val probed = mutableListOf<Path>()
        assertFalse(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":99"
                        "XDG_SESSION_TYPE" -> "wayland"
                        "WAYLAND_DISPLAY" -> "wayland-1"
                        "XDG_RUNTIME_DIR" -> "/run/user/1000"
                        else -> null
                    }
                },
                runtimeDirHasWaylandSocket = { true },
                displayIsPureX11 = { false },
                displayIsXwayland = { false },
                waylandSocketExists = { path ->
                    probed.add(path)
                    false
                },
            )
        )
        assertEquals(listOf(Path.of("/run/user/1000", "wayland-1")), probed)
    }

    @Test
    fun `Wayland detection keeps Wayland when the socket exists and the X server is unknown`() {
        assertTrue(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":0"
                        "WAYLAND_DISPLAY" -> "wayland-0"
                        "XDG_RUNTIME_DIR" -> "/run/user/1000"
                        else -> null
                    }
                },
                displayIsPureX11 = { false },
                displayIsXwayland = { false },
                waylandSocketExists = { it == Path.of("/run/user/1000", "wayland-0") },
            )
        )
    }

    @Test
    fun `Wayland detection trusts an Xwayland DISPLAY without probing further`() {
        assertTrue(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":0"
                        "XDG_SESSION_TYPE" -> "wayland"
                        "WAYLAND_DISPLAY" -> "wayland-0"
                        else -> null
                    }
                },
                displayIsPureX11 = { error("must not probe X server after Xwayland match") },
                displayIsXwayland = { it == ":0" },
                waylandSocketExists = { error("must not probe socket after Xwayland match") },
            )
        )
    }

    @Test
    fun `Wayland detection resolves an absolute WAYLAND_DISPLAY without XDG_RUNTIME_DIR`() {
        // Host-absolute on every OS (a leading "/" is drive-relative on Windows).
        val socket =
            Path.of(System.getProperty("java.io.tmpdir"), "compositor", "wayland-9")
                .toAbsolutePath()
        val probed = mutableListOf<Path>()
        assertTrue(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":0"
                        "WAYLAND_DISPLAY" -> socket.toString()
                        else -> null
                    }
                },
                displayIsPureX11 = { false },
                displayIsXwayland = { false },
                waylandSocketExists = { path ->
                    probed.add(path)
                    true
                },
            )
        )
        assertEquals(listOf(socket), probed)
    }

    @Test
    fun `Wayland detection treats a relative WAYLAND_DISPLAY without XDG_RUNTIME_DIR as X11`() {
        assertFalse(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":99"
                        "WAYLAND_DISPLAY" -> "wayland-0"
                        else -> null
                    }
                },
                displayIsPureX11 = { false },
                displayIsXwayland = { false },
                waylandSocketExists = { error("relative socket cannot be resolved") },
            )
        )
    }

    @Test
    fun `Wayland detection checks the default wayland-0 socket for session type alone`() {
        val probed = mutableListOf<Path>()
        assertFalse(
            isWaylandSession(
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":99"
                        "XDG_SESSION_TYPE" -> "wayland"
                        "XDG_RUNTIME_DIR" -> "/run/user/1000"
                        else -> null
                    }
                },
                displayIsPureX11 = { false },
                displayIsXwayland = { false },
                waylandSocketExists = { path ->
                    probed.add(path)
                    false
                },
            )
        )
        assertEquals(listOf(Path.of("/run/user/1000", "wayland-0")), probed)
    }

    @Test
    fun `Wayland detection skips X server probes when there is no Wayland signal`() {
        assertFalse(
            isWaylandSession(
                getenv = { if (it == "DISPLAY") ":99" else null },
                displayIsPureX11 = { error("no Wayland signal to disprove") },
                displayIsXwayland = { error("no Wayland signal to confirm") },
                waylandSocketExists = { error("no Wayland signal to confirm") },
            )
        )
    }

    @Test
    fun `isWaylandSocket accepts only a Unix domain socket`(@TempDir temp: Path) {
        assumeTrue(
            System.getProperty("os.name").contains("linux", ignoreCase = true),
            "Wayland sockets only exist on Linux",
        )
        val socketPath = temp.resolve("wl-0")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socketPath))
            assertTrue(isWaylandSocket(socketPath))
        }
        val regularFile = Files.createFile(temp.resolve("wl-file"))
        assertFalse(isWaylandSocket(regularFile))
        assertFalse(isWaylandSocket(temp))
        assertFalse(isWaylandSocket(temp.resolve("missing")))
    }

    @Test
    fun `displayServerForCmdline names the X server serving the display`() {
        // Non-display args must not abort the match (see recording twin + Bugbot #401).
        assertEquals(
            "Xvfb",
            displayServerForCmdline(listOf("Xvfb", ":99", "-screen", "0", "1280x1024x24"), ":99"),
        )
        assertEquals(
            "Xwayland",
            displayServerForCmdline(
                listOf("/usr/bin/Xwayland", ":0", "-rootless", "-auth", "/run/user/1000/.x"),
                ":0",
            ),
        )
        assertEquals("Xorg", displayServerForCmdline(listOf("/usr/lib/xorg/Xorg", ":0"), ":0"))
        assertEquals("Xtigervnc", displayServerForCmdline(listOf("Xtigervnc", ":1.0"), ":1"))
        assertEquals("Xvnc", displayServerForCmdline(listOf("/usr/bin/Xvnc", ":2"), ":2"))
        assertEquals("Xephyr", displayServerForCmdline(listOf("Xephyr", "-ac", ":3"), ":3"))
        assertNull(displayServerForCmdline(listOf("Xwayland", ":0"), ":99"))
        assertNull(displayServerForCmdline(listOf("xterm", "-display", ":99"), ":99"))
        assertNull(displayServerForCmdline(listOf(":99", "Xvfb"), ":99"))
    }

    @Test
    fun `localDisplayToken accepts only displays served on this host`() {
        // Remote DISPLAYs must not match local /proc X servers with the same number.
        assertEquals(":0", localDisplayToken(":0"))
        assertEquals(":99", localDisplayToken(":99.0"))
        assertEquals(":1", localDisplayToken("unix:1.0"))
        assertNull(localDisplayToken("remote-host:0"))
        assertNull(localDisplayToken("localhost:10.0"))
        assertNull(localDisplayToken("[::1]:0"))
        assertNull(localDisplayToken("wayland-0"))
        assertNull(linuxDisplayServerName("remote-host:0"))
    }

    @Test
    fun `X server names split into Xwayland and pure X11`() {
        assertTrue(isXwaylandServer("Xwayland"))
        for (name in listOf("Xvfb", "Xvnc", "Xtigervnc", "Xephyr", "Xorg")) {
            assertFalse(isXwaylandServer(name), name)
            assertTrue(isPureX11Server(name), name)
        }
        assertFalse(isPureX11Server("Xwayland"))
        assertFalse(isPureX11Server(null))
    }

    @Test
    fun `normalizeDisplayToken handles Xvfb-style names`() {
        assertEquals(":99", normalizeDisplayToken(":99.0"))
        assertEquals(null, normalizeDisplayToken("Xvfb"))
    }

    @Test
    fun `tracked Frame capture prefers the native window backend`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame().apply {
                setBounds(40, 50, 300, 200)
                addNotify()
            }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        var fallbackCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    fallbackCalls += 1
                    error("should not fall back")
                },
                nativeCapture = { expected },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(frame)).image)
            assertEquals(0, fallbackCalls)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `tracked Dialog capture prefers the native window backend`() {
        assumeLiveAwtAvailable()
        val owner = Frame()
        val dialog =
            Dialog(owner, "visual-idle-dialog").apply {
                setBounds(40, 50, 300, 200)
                addNotify()
            }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        var capturedWindow: Window? = null
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("should not fall back") },
                nativeCapture = { window ->
                    capturedWindow = window
                    expected
                },
                nativeCaptureBounds = { _, _, bounds, _ -> bounds },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(dialog)).image)
            assertSame(dialog, capturedWindow)
        } finally {
            dialog.dispose()
            owner.dispose()
        }
    }

    @Test
    fun `Dialog capture geometry uses its decoration insets`() {
        assumeLiveAwtAvailable()
        val owner = Frame()
        val dialog = Dialog(owner, "decorated-dialog")
        try {
            dialog.addNotify()

            assertEquals(dialog.insets, windowInsets(dialog))
        } finally {
            dialog.dispose()
            owner.dispose()
        }
    }

    @Test
    fun `native helper images normalize to sRGB ARGB`() {
        val source =
            BufferedImage(2, 1, BufferedImage.TYPE_4BYTE_ABGR).apply {
                setRGB(0, 0, 0xFF113355.toInt())
            }
        val captured = normalizeNativeImage(source)
        assertEquals(BufferedImage.TYPE_INT_ARGB, captured.type)
        assertEquals(0xFF113355.toInt(), captured.getRGB(0, 0))
    }

    @Test
    fun `native unavailability fails without capturing a screen region`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame().apply {
                setBounds(40, 50, 300, 200)
                addNotify()
            }
        var regionCaptureCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCaptureCalls += 1
                    error("screen-region capture must not be used")
                },
                nativeCapture = {
                    throw IllegalStateException("macOS window screenshot is unavailable")
                },
            )
        try {
            val error =
                assertFailsWith<IllegalStateException> { backend.captureWindow(tracked(frame)) }
            assertTrue(error.message.orEmpty().contains("macOS window screenshot is unavailable"))
            assertEquals(0, regionCaptureCalls)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `disabled native capture preserves the driver's loud failure`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame().apply {
                setBounds(40, 50, 300, 200)
                addNotify()
            }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { error("native capture should be disabled") },
                nativeCaptureEnabled = { false },
            )
        try {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    backend.captureWindow(tracked(frame))
                }
            assertTrue(error.message.orEmpty().contains("disabled"))
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `duplicate window titles fail with matching window bounds`() {
        assumeLiveAwtAvailable()
        val first =
            Frame("same title").apply {
                setBounds(10, 20, 100, 80)
                addNotify()
            }
        val second =
            Frame("same title").apply {
                setBounds(120, 30, 100, 80)
                addNotify()
            }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { error("native capture should not be used for duplicate titles") },
            )
        try {
            val error =
                assertFailsWith<IllegalStateException> { backend.captureWindow(tracked(second)) }
            assertTrue(error.message.orEmpty().contains("same title"))
            val message = error.message.orEmpty()
            assertTrue(message.contains(first.bounds.toString()))
            assertTrue(message.contains(second.bounds.toString()))
        } finally {
            first.dispose()
            second.dispose()
        }
    }

    @Test
    fun `disposed tracked frame fails before title-based native capture`() {
        assumeLiveAwtAvailable()
        val target = Frame("same title").apply { addNotify() }
        val other = Frame("same title").apply { addNotify() }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { error("native capture must not select another frame") },
            )
        try {
            target.dispose()

            val error =
                assertFailsWith<IllegalStateException> { backend.captureWindow(tracked(target)) }

            assertTrue(error.message.orEmpty().contains("no longer displayable"))
        } finally {
            target.dispose()
            other.dispose()
        }
    }

    @Test
    fun `image hash excludes pixels outside a cropped subimage`() {
        val parent = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        val crop = parent.getSubimage(0, 0, 2, 2)
        val initialHash = imageHash(crop)

        parent.setRGB(3, 1, 0xFF00FF00.toInt())

        assertEquals(initialHash, imageHash(crop))
    }

    private fun tracked(window: Window): TrackedWindow =
        TrackedWindow("test", window, composePanel = null, isPopup = false)
}

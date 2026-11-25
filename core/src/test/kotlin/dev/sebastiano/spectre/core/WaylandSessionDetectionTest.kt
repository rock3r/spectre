@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

/** Linux session detection behind [isWaylandSession] (#397, #575, #607). */
class WaylandSessionDetectionTest {
    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    fun `non-Linux hosts never consult Linux session evidence`() {
        val original = System.getProperty("os.name")
        try {
            for (osName in listOf("Windows 11", "Mac OS X")) {
                System.setProperty("os.name", osName)
                assertFalse(isWaylandSession(getenv = { null }), osName)
                assertFalse(
                    isWaylandSession(getenv = { error("unexpected Linux lookup: $it") }),
                    osName,
                )
                assertFalse(isWaylandSession(), osName)
            }
        } finally {
            System.setProperty("os.name", original)
        }
    }

    @Test
    fun `session detection rejects an unproven session instead of guessing X11`() {
        val error =
            assertFailsWith<IllegalStateException> {
                isWaylandSession(
                    osName = "Linux",
                    getenv = { null },
                    runtimeDirHasWaylandSocket = { false },
                    displayIsPureX11 = { false },
                    displayIsXwayland = { false },
                )
            }
        assertTrue(error.message.orEmpty().contains("SPECTRE_CAPTURE_BACKEND"))
    }

    @Test
    fun `Wayland detection recognizes a runtime-directory socket without DISPLAY`() {
        // SSH into a Wayland host: no DISPLAY, residual compositor socket → Wayland.
        assertTrue(
            isWaylandSession(
                osName = "Linux",
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
                osName = "Linux",
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":99"
                        "XDG_RUNTIME_DIR" -> "/run/user/1000"
                        else -> null
                    }
                },
                runtimeDirHasWaylandSocket = { true },
                displayIsPureX11 = { true },
                displayIsXwayland = { false },
            )
        )
    }

    @Test
    fun `Wayland detection prefers pure X11 DISPLAY over inherited Wayland env`() {
        assertFalse(
            isWaylandSession(
                osName = "Linux",
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
                displayIsXwayland = { false },
            )
        )
    }

    @Test
    fun `Wayland detection keeps portal routing for XWayland DISPLAY`() {
        assertTrue(
            isWaylandSession(
                osName = "Linux",
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
                osName = "Linux",
                getenv = { key ->
                    when (key) {
                        "SPECTRE_CAPTURE_BACKEND" -> "x11"
                        "XDG_SESSION_TYPE" -> "wayland"
                        "WAYLAND_DISPLAY" -> "wayland-0"
                        else -> null
                    }
                },
            )
        )
        assertTrue(
            isWaylandSession(
                osName = "Linux",
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
                osName = "Linux",
                getenv = { key ->
                    if (key == "XDG_SESSION_TYPE") "x11"
                    else if (key == "XDG_RUNTIME_DIR") "/run/user/1000" else null
                },
                runtimeDirHasWaylandSocket = {
                    error("must not probe after explicit session type")
                },
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
                osName = "Linux",
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
                displayIsPureX11 = { true },
                displayIsXwayland = { false },
                waylandSocketExists = { path ->
                    probed.add(path)
                    false
                },
            )
        )
        assertTrue(probed.isEmpty(), "a proven X11 server needs no socket fallback")
    }

    @Test
    fun `Wayland detection keeps Wayland when the socket exists and the X server is unknown`() {
        assertTrue(
            isWaylandSession(
                osName = "Linux",
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
                osName = "Linux",
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
                osName = "Linux",
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
                osName = "Linux",
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":99"
                        "XDG_SESSION_TYPE" -> "x11"
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
    fun `Wayland detection accepts the default wayland-0 socket for session type alone`() {
        val probed = mutableListOf<Path>()
        assertTrue(
            isWaylandSession(
                osName = "Linux",
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
                    true
                },
            )
        )
        assertEquals(listOf(Path.of("/run/user/1000", "wayland-0")), probed)
    }

    @Test
    fun `Wayland detection without a Wayland signal probes only for Xwayland`() {
        assertFalse(
            isWaylandSession(
                osName = "Linux",
                getenv = { if (it == "DISPLAY") ":99" else null },
                displayIsPureX11 = { true },
                displayIsXwayland = { false },
                waylandSocketExists = { error("no Wayland signal to confirm") },
            )
        )
    }

    @Test
    fun `Wayland detection routes an Xwayland DISPLAY reached over SSH to Wayland`() {
        // #607: SSH into a GNOME Wayland seat sets DISPLAY=:0 (Xwayland) and
        // XDG_SESSION_TYPE=tty, with no WAYLAND_DISPLAY. Xwayland still means Wayland: X11
        // capture is black and XTest input stalls there.
        assertTrue(
            isWaylandSession(
                osName = "Linux",
                getenv = { key ->
                    when (key) {
                        "DISPLAY" -> ":0"
                        "XDG_SESSION_TYPE" -> "tty"
                        "XDG_RUNTIME_DIR" -> "/run/user/1000"
                        else -> null
                    }
                },
                runtimeDirHasWaylandSocket = { true },
                displayIsPureX11 = { error("must not probe X server after Xwayland match") },
                displayIsXwayland = { it == ":0" },
                waylandSocketExists = { error("must not probe socket after Xwayland match") },
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
}

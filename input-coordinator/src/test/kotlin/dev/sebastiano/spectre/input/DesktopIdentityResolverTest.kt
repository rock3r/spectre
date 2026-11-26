@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopIdentityResolverTest {

    private val resolver =
        DesktopIdentityResolver(
            canonicalPathResolver = { path -> Path.of("canonical-${path.fileName}") },
            linuxDisplayProbe = { LinuxDisplayEvidence.PureX11 },
        )

    @Test
    fun `equivalent local X11 display spellings resolve to one resource`() {
        val variants =
            listOf(
                ":0",
                ":0.0",
                ":0.1",
                "unix:0",
                "unix:0.1",
                "unix/:0",
                "unix/:0.99",
                "localhost:0",
                "localhost:0.1",
                "127.0.0.1:0",
                "127.0.0.1:0.99",
            )

        val keys = variants.map { display ->
            resolver.resolve(
                DesktopIdentityEnvironment(
                    platform = DesktopPlatform.LINUX,
                    effectiveUserId = "1000",
                    environment = mapOf("DISPLAY" to display),
                )
            )
        }

        assertEquals(1, keys.distinct().size)
        assertEquals("user:1000/x11-local:0", keys.distinct().single().value)
    }

    @Test
    fun `remote X11 displays remain distinct`() {
        val first = linux(display = "host-a:10.0")
        val second = linux(display = "host-b:10.0")

        assertEquals("user:1000/x11-remote:host-a:10", first.value)
        assertEquals("user:1000/x11-remote:host-b:10", second.value)
    }

    @Test
    fun `remote X11 screens share one server identity`() {
        assertEquals(linux("host.example:10"), linux("host.example:10.0"))
        assertEquals(linux("host.example:10"), linux("host.example:10.1"))
    }

    @Test
    fun `remote X11 host names ignore DNS casing`() {
        assertEquals(linux("host.example:10.0"), linux("Host.Example:10.1"))
    }

    @Test
    fun `Wayland socket spellings use canonical path`() {
        val key =
            resolver.resolve(
                DesktopIdentityEnvironment(
                    platform = DesktopPlatform.LINUX,
                    effectiveUserId = "1000",
                    environment =
                        mapOf(
                            "WAYLAND_DISPLAY" to "wayland-0",
                            "XDG_RUNTIME_DIR" to "/run/user/1000",
                        ),
                )
            )

        assertEquals("user:1000/wayland:canonical-wayland-0", key.value)
    }

    @Test
    fun `positive Xvfb evidence keeps a local display on its X11 lease`() {
        val key = linux(":99")

        assertEquals("user:1000/x11-local:99", key.value)
    }

    @Test
    fun `Xwayland and native Wayland clients share the proven compositor lease`() {
        val resolver =
            DesktopIdentityResolver(
                canonicalPathResolver = { path -> Path.of("canonical-${path.fileName}") },
                linuxDisplayProbe = {
                    LinuxDisplayEvidence.Xwayland(Path.of("/run/user/1000/wayland-1"))
                },
            )

        val xwayland =
            resolver.resolve(
                DesktopIdentityEnvironment(
                    DesktopPlatform.LINUX,
                    "1000",
                    mapOf("DISPLAY" to ":7"),
                )
            )
        val native =
            resolver.resolve(
                DesktopIdentityEnvironment(
                    DesktopPlatform.LINUX,
                    "1000",
                    mapOf(
                        "WAYLAND_DISPLAY" to "wayland-1",
                        "XDG_RUNTIME_DIR" to "/run/user/1000",
                    ),
                )
            )

        assertEquals(native, xwayland)
    }

    @Test
    fun `hidden proc or unreadable lineage fails visibly instead of inventing X11`() {
        val resolver =
            DesktopIdentityResolver(
                canonicalPathResolver = { it },
                linuxDisplayProbe = {
                    LinuxDisplayEvidence.Unknown("process command lines were unreadable")
                },
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                resolver.resolve(
                    DesktopIdentityEnvironment(
                        DesktopPlatform.LINUX,
                        "1000",
                        mapOf("DISPLAY" to ":0"),
                    )
                )
            }

        assertTrue(failure.message.orEmpty().contains("Cannot prove"), failure.message)
        assertTrue(failure.message.orEmpty().contains("unreadable"), failure.message)
    }

    @Test
    fun `relative Wayland display without runtime directory fails visibly`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                resolver.resolve(
                    DesktopIdentityEnvironment(
                        DesktopPlatform.LINUX,
                        "1000",
                        mapOf("WAYLAND_DISPLAY" to "wayland-0"),
                    )
                )
            }

        assertTrue(failure.message.orEmpty().contains("XDG_RUNTIME_DIR"), failure.message)
    }

    @Test
    fun `canonical Wayland socket failure is not replaced by a lexical identity`() {
        val socketPath =
            Path.of(System.getProperty("java.io.tmpdir")).resolve("wayland-0").toAbsolutePath()
        var canonicalizedPath: Path? = null
        val resolver =
            DesktopIdentityResolver(
                canonicalPathResolver = {
                    canonicalizedPath = it
                    throw java.io.IOException("unreadable")
                },
                linuxDisplayProbe = { LinuxDisplayEvidence.PureX11 },
            )

        val failure =
            assertFailsWith<IllegalStateException> {
                resolver.resolve(
                    DesktopIdentityEnvironment(
                        DesktopPlatform.LINUX,
                        "1000",
                        mapOf("WAYLAND_DISPLAY" to socketPath.toString()),
                    )
                )
            }

        assertEquals(socketPath, canonicalizedPath)
        assertEquals("Cannot canonicalize Wayland compositor socket $socketPath", failure.message)
        assertTrue(failure.cause is java.io.IOException, failure.cause.toString())
        assertEquals("unreadable", failure.cause?.message)
    }

    @Test
    fun `non X server executable cannot qualify by mentioning Xorg and a display`() {
        assertNull(
            displayServerFor(
                arguments = listOf("python3", "worker.py", "/usr/bin/Xorg", ":88"),
                token = ":88",
                executable = Path.of("/usr/bin/python3"),
            )
        )
    }

    @Test
    fun `recognized X server executable qualifies with matching display argument`() {
        assertEquals(
            "Xvfb",
            displayServerFor(
                arguments = listOf("/usr/bin/Xvfb", ":88", "-screen", "0", "1024x768x24"),
                token = ":88",
                executable = Path.of("/usr/bin/Xvfb"),
            ),
        )
    }

    @Test
    fun `macOS conservatively serializes per console user`() {
        val key =
            resolver.resolve(
                DesktopIdentityEnvironment(
                    platform = DesktopPlatform.MACOS,
                    effectiveUserId = "501",
                )
            )

        assertEquals("user:501/macos-console", key.value)
    }

    @Test
    fun `Windows uses only verified numeric session ids and otherwise serializes per user`() {
        val withSession =
            resolver.resolve(
                DesktopIdentityEnvironment(
                    platform = DesktopPlatform.WINDOWS,
                    effectiveUserId = "seb",
                    windowsLogonSessionId = "7",
                )
            )
        val transportName =
            resolver.resolve(
                DesktopIdentityEnvironment(
                    platform = DesktopPlatform.WINDOWS,
                    effectiveUserId = "seb",
                    windowsLogonSessionId = "RDP-Tcp#5",
                )
            )
        val fallback =
            resolver.resolve(
                DesktopIdentityEnvironment(
                    platform = DesktopPlatform.WINDOWS,
                    effectiveUserId = "seb",
                )
            )

        assertEquals("user:seb/windows-session:7", withSession.value)
        assertEquals("user:seb/windows-user", transportName.value)
        assertEquals("user:seb/windows-user", fallback.value)
    }

    @Test
    fun `display server names in innocent process arguments are ignored`() {
        assertEquals(
            null,
            displayServerFor(
                arguments = listOf("/usr/bin/java", "Xwayland", ":7"),
                executable = Path.of("/usr/bin/java"),
                token = ":7",
            ),
        )
    }

    @Test
    fun `proc executable can establish display server identity`() {
        assertEquals(
            "Xorg",
            displayServerFor(
                arguments = listOf("desktop-wrapper", ":7"),
                executable = Path.of("/usr/libexec/Xorg"),
                token = ":7",
            ),
        )
    }

    private fun linux(display: String): DesktopResourceKey =
        resolver.resolve(
            DesktopIdentityEnvironment(
                platform = DesktopPlatform.LINUX,
                effectiveUserId = "1000",
                environment = mapOf("DISPLAY" to display),
            )
        )
}

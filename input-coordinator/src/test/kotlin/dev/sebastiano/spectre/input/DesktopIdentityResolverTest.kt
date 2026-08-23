@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopIdentityResolverTest {

    private val resolver = DesktopIdentityResolver { path ->
        Path.of("/canonical", path.fileName.toString())
    }

    @Test
    fun `equivalent local X11 display spellings resolve to one resource`() {
        val variants = listOf(":0", ":0.0", ":0.1", "unix/:0", "unix/:0.99")

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

        assertEquals("user:1000/x11-remote:host-a:10.0", first.value)
        assertEquals("user:1000/x11-remote:host-b:10.0", second.value)
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

        assertEquals("user:1000/wayland:/canonical/wayland-0", key.value)
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

    private fun linux(display: String): DesktopResourceKey =
        resolver.resolve(
            DesktopIdentityEnvironment(
                platform = DesktopPlatform.LINUX,
                effectiveUserId = "1000",
                environment = mapOf("DISPLAY" to display),
            )
        )
}

package dev.sebastiano.spectre.recording.portal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `spectre permissions check|request` on Linux Wayland: one RemoteDesktop grant, asked once. */
class WaylandPortalAccessTest {

    @TempDir lateinit var tempDir: Path

    @Test
    fun `preflight is not applicable off Linux Wayland and never touches the session`() {
        val grants = grants(applicable = false, openSession = { error("must not open a session") })

        val result = grants.preflight()

        assertEquals(WaylandPortalAccessStatus.NOT_APPLICABLE, result.status)
        assertTrue(result.relayMessage().contains("not applicable"), result.relayMessage())
    }

    @Test
    fun `preflight reports a missing grant with the request command`() {
        val result = grants().preflight()

        assertEquals(WaylandPortalAccessStatus.MISSING, result.status)
        assertFalse(result.granted)
        assertFalse(result.tokenStored)
        assertEquals(tokenFile().toString(), result.tokenPath)
        assertTrue(result.guidance.contains("spectre permissions request"), result.guidance)
    }

    @Test
    fun `a stored token counts as granted`() {
        storeToken()

        val result = grants().preflight()

        assertEquals(WaylandPortalAccessStatus.GRANTED, result.status)
        assertTrue(result.tokenStored)
        assertFalse(result.sessionActive)
    }

    @Test
    fun `a blank token file is not a grant`() {
        Files.createDirectories(tokenFile().parent)
        Files.writeString(tokenFile(), "  \n")

        assertEquals(WaylandPortalAccessStatus.MISSING, grants().preflight().status)
    }

    @Test
    fun `a running session counts as granted`() {
        val socket = Files.createFile(tempDir.resolve("wayland-session.sock"))

        val result = grants(socket = socket, live = true).preflight()

        assertEquals(WaylandPortalAccessStatus.GRANTED, result.status)
        assertTrue(result.sessionActive)
        assertEquals(socket.toString(), result.sessionSocket)
    }

    @Test
    fun `request opens the session with the attended timeout and reports the stored grant`() {
        var requestedTimeout = 0L
        val grants =
            grants(
                openSession = { timeout ->
                    requestedTimeout = timeout
                    storeToken()
                }
            )

        val result = grants.request(timeoutSeconds = 300)

        assertEquals(300, requestedTimeout)
        assertEquals(WaylandPortalAccessStatus.GRANTED, result.status)
        assertTrue(result.tokenStored)
    }

    @Test
    fun `request does nothing when a session already runs`() {
        val socket = Files.createFile(tempDir.resolve("wayland-session.sock"))
        val grants =
            grants(socket = socket, live = true, openSession = { error("no second session") })

        assertEquals(WaylandPortalAccessStatus.GRANTED, grants.request(300).status)
    }

    @Test
    fun `request reports an unanswered dialog as missing`() {
        val grants = grants(openSession = { throw WaylandConsentNotGrantedException("Timed out") })

        val result = grants.request(timeoutSeconds = 120)

        assertEquals(WaylandPortalAccessStatus.MISSING, result.status)
        assertTrue(result.guidance.contains("120s"), result.guidance)
        assertTrue(result.guidance.contains("spectre permissions request"), result.guidance)
    }

    @Test
    fun `the consent prompt policy prefers the system property`() {
        val never = { _: String -> "never" }
        val unset = { _: String -> null }
        assertEquals(WaylandConsentPrompt.NEVER, waylandConsentPrompt(never, unset))
        assertEquals(WaylandConsentPrompt.NEVER, waylandConsentPrompt(unset, { " OFF " }))
        assertEquals(WaylandConsentPrompt.ALLOW, waylandConsentPrompt(unset, unset))
        assertEquals(WaylandConsentPrompt.ALLOW, waylandConsentPrompt({ "allow" }, never))
    }

    @Test
    fun `the token path mirrors the helper's resolution order`() {
        val name = "wayland-rd-restore-token-rd-monitor-embedded"
        assertEquals(
            Path.of("/d", name),
            waylandRemoteDesktopTokenPath(
                mapOf("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR" to "/d", "HOME" to "/h")::get
            ),
        )
        assertEquals(
            Path.of("/s", "spectre", name),
            waylandRemoteDesktopTokenPath(mapOf("XDG_STATE_HOME" to "/s", "HOME" to "/h")::get),
        )
        assertEquals(
            Path.of("/h", ".local", "state", "spectre", name),
            waylandRemoteDesktopTokenPath(
                mapOf("HOME" to "/h", "SPECTRE_WAYLAND_RESTORE_TOKEN_DIR" to "")::get
            ),
        )
        assertEquals(
            Path.of("/p", "tok-rd-monitor-embedded"),
            waylandRemoteDesktopTokenPath(
                mapOf("SPECTRE_WAYLAND_RESTORE_TOKEN_PATH" to "/p/tok")::get
            ),
        )
        assertEquals(
            Path.of("/p", name),
            waylandRemoteDesktopTokenPath(
                mapOf("SPECTRE_WAYLAND_RESTORE_TOKEN_PATH" to "/p/$name")::get
            ),
        )
        assertNull(waylandRemoteDesktopTokenPath { null })
    }

    private fun tokenFile(): Path =
        tempDir.resolve("tokens").resolve(remoteDesktopTokenFileName(RD_SESSION_TOKEN_KEY))

    private fun storeToken() {
        Files.createDirectories(tokenFile().parent)
        Files.writeString(tokenFile(), "token")
    }

    private fun grants(
        applicable: Boolean = true,
        socket: Path? = tempDir.resolve("missing.sock"),
        live: Boolean = false,
        openSession: (Long) -> Unit = {},
    ): WaylandPortalGrants =
        WaylandPortalGrants(
            isApplicable = { applicable },
            envLookup =
                mapOf("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR" to tempDir.resolve("tokens").toString())::
                    get,
            sessionSocket = { socket },
            socketIsLive = { live },
            openSession = openSession,
        )
}

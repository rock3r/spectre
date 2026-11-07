package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import dev.sebastiano.spectre.recording.portal.WaylandPortalAccessResult
import dev.sebastiano.spectre.recording.portal.WaylandPortalAccessStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `spectre permissions check|request` on Linux Wayland, with a fake portal grant. */
class WaylandPermissionsCliTest {

    @Test
    fun `check reports a missing grant and exits 1 without opening a dialog`() {
        val access = FakeWaylandPermissions(granted = false)
        val out = StringBuilder()

        val exit =
            assertFailsWith<ProgramResult> {
                PermissionsCommand(out, PermissionsPlatform.LinuxWayland(access))
                    .parse(listOf("check"))
            }

        assertEquals(1, exit.statusCode)
        assertEquals(0, access.requests, "check must never request the grant")
        assertTrue(out.contains("MISSING"), out.toString())
        assertTrue(out.contains("spectre permissions request"), out.toString())
    }

    @Test
    fun `request passes the timeout and succeeds when the grant is stored`() {
        val access = FakeWaylandPermissions(granted = true)
        val out = StringBuilder()

        PermissionsCommand(out, PermissionsPlatform.LinuxWayland(access))
            .parse(listOf("request", "--timeout", "120"))

        assertEquals(listOf(120L), access.timeouts)
        assertTrue(out.contains("Share"), out.toString())
        assertTrue(out.contains("granted"), out.toString())
    }

    @Test
    fun `check --json reports the Wayland grant fields`() {
        val out = StringBuilder()

        PermissionsCommand(
                out,
                PermissionsPlatform.LinuxWayland(FakeWaylandPermissions(granted = true)),
            )
            .parse(listOf("check", "--json"))

        val text = out.toString()
        assertTrue(text.contains("\"platform\":\"linux-wayland\""), text)
        assertTrue(text.contains("\"granted\":true"), text)
        assertTrue(text.contains("\"tokenPath\":\"/state/token\""), text)
    }

    @Test
    fun `other platforms say no permission is needed`() {
        val out = StringBuilder()

        PermissionsCommand(out, PermissionsPlatform.NotNeeded).parse(listOf("request"))

        assertTrue(out.contains("not needed"), out.toString())
    }

    private class FakeWaylandPermissions(private val granted: Boolean) : WaylandPermissions {
        var requests = 0
        val timeouts = mutableListOf<Long>()

        override fun preflight(): WaylandPortalAccessResult = result()

        override fun request(timeoutSeconds: Long): WaylandPortalAccessResult {
            requests += 1
            timeouts += timeoutSeconds
            return result()
        }

        private fun result() =
            WaylandPortalAccessResult(
                status =
                    if (granted) WaylandPortalAccessStatus.GRANTED
                    else WaylandPortalAccessStatus.MISSING,
                tokenStored = granted,
                tokenPath = "/state/token",
                guidance = if (granted) "" else "Run `spectre permissions request` once.",
            )
    }
}

package dev.sebastiano.spectre.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacOsRecordingPermissionsTest {

    @Test
    fun `non-macOS platforms report NotApplicable`() {
        if (System.getProperty("os.name").lowercase().contains("mac")) return
        val diag = MacOsRecordingPermissions.diagnose()
        assertEquals(PermissionStatus.NotApplicable, diag.status)
        assertTrue(diag.message.contains("not applicable"))
    }

    @Test
    fun `diagnose always returns a non-empty message`() {
        // Regardless of platform, the diagnostic message must be non-blank — downstream tooling
        // dumps it at startup and we want a useful line either way.
        val diag = MacOsRecordingPermissions.diagnose()
        assertTrue(diag.message.isNotBlank())
    }

    @Test
    fun `PermissionStatus labels are stable user-facing strings`() {
        // These are surfaced verbatim in the diagnostic message.
        assertEquals("granted", PermissionStatus.Granted.label)
        assertEquals("denied", PermissionStatus.Denied.label)
        assertTrue(PermissionStatus.Unknown.label.startsWith("unknown"))
        assertEquals("not applicable on this platform", PermissionStatus.NotApplicable.label)
    }
}

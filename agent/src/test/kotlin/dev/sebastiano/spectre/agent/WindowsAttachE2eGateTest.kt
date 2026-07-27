package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the physical-desktop opt-in that keeps hosted `windows-latest` skip-safe while
 * allowing Mattone-style desktops to run [AgentAttachIntegrationTest].
 */
class WindowsAttachE2eGateTest {
    @Test
    fun `non-Windows platforms are always allowed`() {
        assertTrue(WindowsAttachE2eGate.isAllowed(osName = "Mac OS X", allowWindows = null))
        assertTrue(WindowsAttachE2eGate.isAllowed(osName = "Linux", allowWindows = "false"))
    }

    @Test
    fun `Windows requires the allow property to be exactly true`() {
        assertFalse(WindowsAttachE2eGate.isAllowed(osName = "Windows 11", allowWindows = null))
        assertFalse(WindowsAttachE2eGate.isAllowed(osName = "Windows 11", allowWindows = "false"))
        assertFalse(WindowsAttachE2eGate.isAllowed(osName = "Windows 11", allowWindows = "TRUE"))
        assertTrue(WindowsAttachE2eGate.isAllowed(osName = "Windows 11", allowWindows = "true"))
        assertTrue(WindowsAttachE2eGate.isAllowed(osName = "windows 10", allowWindows = "true"))
    }
}

@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchDescendantDiscoveryTest {

    @Test
    fun `isGradleDaemonDisplayName matches common daemon banners`() {
        assertTrue(LaunchDescendantDiscovery.isGradleDaemonDisplayName("GradleDaemon 8.14"))
        assertTrue(
            LaunchDescendantDiscovery.isGradleDaemonDisplayName(
                "org.gradle.launcher.daemon.bootstrap.GradleDaemon"
            )
        )
        assertTrue(LaunchDescendantDiscovery.isGradleDaemonDisplayName("gradle-daemon"))
        assertFalse(
            LaunchDescendantDiscovery.isGradleDaemonDisplayName(
                "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt"
            )
        )
        assertFalse(LaunchDescendantDiscovery.isGradleDaemonDisplayName("SampleDesktopKt"))
    }

    @Test
    fun `discoverAppJvm returns null for impossible nameFilter`() {
        val self = ProcessHandle.current().pid()
        assertNull(
            LaunchDescendantDiscovery.discoverAppJvm(
                clientPid = self,
                nameFilter = "DefinitelyNotARealMainClass_issue208_xyz",
            )
        )
    }

    @Test
    fun `discoverAppJvm never returns a Gradle daemon pid`() {
        val self = ProcessHandle.current().pid()
        val found = LaunchDescendantDiscovery.discoverAppJvm(self, nameFilter = null)
        if (found != null) {
            // If structural discovery found something on this machine, it must not be a daemon.
            val listed = dev.sebastiano.spectre.agent.SpectreProcesses.listJvmProcesses()
            val display = listed.firstOrNull { it.pid == found }?.displayName.orEmpty()
            assertFalse(
                LaunchDescendantDiscovery.isGradleDaemonDisplayName(display),
                "discoverAppJvm returned daemon pid=$found displayName='$display'",
            )
            assertTrue(found != self, "must not return the client pid itself")
        }
    }
}

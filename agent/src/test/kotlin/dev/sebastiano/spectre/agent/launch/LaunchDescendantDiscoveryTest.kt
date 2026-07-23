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
    fun `discoverAppJvm returns null when client pid has no descendants`() {
        // Current process has no child JVMs in a normal unit-test run; must not fall back to
        // attaching an arbitrary machine-wide JVM (e.g. the lowest listed PID).
        val self = ProcessHandle.current().pid()
        assertNull(LaunchDescendantDiscovery.discoverAppJvm(self, nameFilter = null))
    }

    @Test
    fun `discoverAppJvm returns null for missing client pid`() {
        assertNull(LaunchDescendantDiscovery.discoverAppJvm(Long.MAX_VALUE / 3, nameFilter = null))
    }
}

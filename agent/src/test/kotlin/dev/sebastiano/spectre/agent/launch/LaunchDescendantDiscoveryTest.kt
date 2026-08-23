@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.JvmProcessInfo
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
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

    // ---------------------------------------------------------------------------------------
    // #446: a JVM that was already running when the launch started cannot belong to that launch.
    //
    // `./gradlew check` runs other agent e2es that spawn the same Compose fixture main class
    // directly, and those are ProcessHandle descendants of the same Gradle daemon. When one of
    // them is still alive — or a crashed earlier run left one behind — it matches the name filter
    // just as well as the JVM this launch is waiting for, and discovery picked it. Attaching there
    // loaded the agent into a JVM that never binds the launch's UDS path, so AGENT_BOOTSTRAP timed
    // out 30s later against a process that was never the target.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a candidate that started before the launch cannot belong to it`() {
        val launchedAt = Instant.parse("2026-08-23T10:00:00Z")
        assertTrue(
            LaunchDescendantDiscovery.predatesLaunch(
                candidateStart = launchedAt.minusSeconds(600),
                clientStart = launchedAt,
            )
        )
    }

    @Test
    fun `a candidate that started after the launch can belong to it`() {
        val launchedAt = Instant.parse("2026-08-23T10:00:00Z")
        assertFalse(
            LaunchDescendantDiscovery.predatesLaunch(
                candidateStart = launchedAt.plusSeconds(5),
                clientStart = launchedAt,
            )
        )
        // Equal instants are not proof of anything; clocks are coarse.
        assertFalse(
            LaunchDescendantDiscovery.predatesLaunch(
                candidateStart = launchedAt,
                clientStart = launchedAt,
            )
        )
    }

    @Test
    fun `an unknown start time keeps the candidate`() {
        // Not every platform and permission setup exposes process start times. Failing closed
        // there would break discovery entirely, which is far worse than the race it guards.
        val launchedAt = Instant.parse("2026-08-23T10:00:00Z")
        assertFalse(
            LaunchDescendantDiscovery.predatesLaunch(
                candidateStart = null,
                clientStart = launchedAt,
            )
        )
        assertFalse(
            LaunchDescendantDiscovery.predatesLaunch(
                candidateStart = launchedAt.minusSeconds(600),
                clientStart = null,
            )
        )
    }

    @Test
    fun `selectAppJvm skips a leftover fixture and picks the one this launch started`() {
        // The leftover deliberately holds the *higher* pid, so "highest pid wins" cannot be what
        // saves this: only the start-time gate can tell the two fixtures apart.
        val scenario = GradleLaunchScenario()
        assertEquals(
            scenario.freshFixturePid,
            scenario.select(nameFilter = "ComposeFixtureMain"),
            "must pick the fixture started by this launch, not the leftover one",
        )
    }

    @Test
    fun `selectAppJvm returns null while only a leftover fixture is running`() {
        // The real fixture has not registered yet. Returning null keeps awaitJvmAttachable polling
        // instead of committing the attach to the wrong JVM.
        val scenario = GradleLaunchScenario(includeFreshFixture = false)
        assertNull(scenario.select(nameFilter = "ComposeFixtureMain"))
    }

    @Test
    fun `selectAppJvm keeps rejecting a leftover after the gradle client has exited`() {
        // Gradle-ish discovery deliberately keeps polling after the client exits — the app JVM is
        // a daemon child that often appears later. Once the client is reaped,
        // ProcessHandle.of(clientPid) is empty, so a boundary resolved per poll would be null and
        // predatesLaunch would fail open, re-admitting the very leftover the gate exists to
        // reject. The boundary has to be captured once, while the client is alive.
        val scenario = GradleLaunchScenario(includeFreshFixture = false, clientReaped = true)
        assertNull(scenario.select(nameFilter = "ComposeFixtureMain"))
    }

    @Test
    fun `selectAppJvm still finds the fresh fixture after the gradle client has exited`() {
        val scenario = GradleLaunchScenario(clientReaped = true)
        assertEquals(scenario.freshFixturePid, scenario.select(nameFilter = "ComposeFixtureMain"))
    }

    @Test
    fun `selectAppJvm still walks the native tree when every listed JVM predates the launch`() {
        // VirtualMachine.list() lags behind spawn, and -XX:-UsePerfData hides a JVM from it
        // entirely — the native process-tree walk is the fallback for exactly that. Filtering the
        // listed set down to nothing must not short-circuit past it, or a launch whose target is
        // only discoverable natively polls until timeout.
        val scenario = GradleLaunchScenario(includeFreshFixture = false)
        assertEquals(
            NATIVE_FALLBACK_PID,
            scenario.select(
                nameFilter = "ComposeFixtureMain",
                nativeFallback = { _, _, _ -> NATIVE_FALLBACK_PID },
            ),
        )
    }

    @Test
    fun `selectAppJvm falls back to the highest matching pid when start times are unknown`() {
        // Fail-open: with no start times to compare, selection is exactly what it was before the
        // gate — highest matching pid, leftover included. Worse than the gate, better than no
        // discovery at all on a platform that hides process start times.
        val scenario = GradleLaunchScenario(startTimesKnown = false)
        assertEquals(
            scenario.leftoverFixturePid,
            scenario.select(nameFilter = "ComposeFixtureMain"),
        )
    }

    /**
     * The #446 process layout: a gradlew client, a long-lived Gradle daemon, a leftover fixture
     * from an earlier run hanging off that daemon, and the fixture this launch actually started.
     */
    private class GradleLaunchScenario(
        includeFreshFixture: Boolean = true,
        private val startTimesKnown: Boolean = true,
        /** The gradlew client has been reaped, so its pid no longer resolves to a handle. */
        private val clientReaped: Boolean = false,
    ) {
        val clientPid: Long = 12_300
        val daemonPid: Long = 12_310
        val leftoverFixturePid: Long = 12_900
        val freshFixturePid: Long = 12_672

        val launchedAt: Instant = Instant.parse("2026-08-23T10:00:00Z")

        private val listed: List<JvmProcessInfo> = buildList {
            add(JvmProcessInfo(daemonPid, "org.gradle.launcher.daemon.bootstrap.GradleDaemon 8.14"))
            add(
                JvmProcessInfo(
                    leftoverFixturePid,
                    "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt",
                )
            )
            if (includeFreshFixture) {
                add(
                    JvmProcessInfo(
                        freshFixturePid,
                        "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt",
                    )
                )
            }
        }

        private val startInstants: Map<Long, Instant> =
            mapOf(
                clientPid to launchedAt,
                daemonPid to launchedAt.minusSeconds(3_600),
                leftoverFixturePid to launchedAt.minusSeconds(600),
                freshFixturePid to launchedAt.plusSeconds(5),
            )

        fun select(
            nameFilter: String?,
            nativeFallback: (Long, Set<Long>, String?) -> Long? = { _, _, _ -> null },
        ): Long? =
            LaunchDescendantDiscovery.selectAppJvm(
                clientPid = clientPid,
                nameFilter = nameFilter,
                // Captured once by awaitJvmAttachable while the client was still alive.
                clientStart = launchedAt.takeIf { startTimesKnown },
                listed = listed,
                descendantsOf = { emptySet() },
                parentOf = { pid -> daemonPid.takeIf { pid != daemonPid && pid != clientPid } },
                startInstantOf = { pid ->
                    startInstants[pid].takeIf {
                        startTimesKnown && !(clientReaped && pid == clientPid)
                    }
                },
                nativeFallback = nativeFallback,
            )
    }

    private companion object {
        const val NATIVE_FALLBACK_PID = 99_999L
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

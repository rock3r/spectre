@file:OptIn(
    dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class,
    dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class,
)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.AttachInputCoordination
import dev.sebastiano.spectre.core.InputLeasePolicy
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpectreAgentInputCoordinationCompatibilityTest {

    @Test
    fun `attach constructs a synthetic driver when the target companion offers one`() {
        val source =
            Path.of("src/main/kotlin/dev/sebastiano/spectre/agent/runtime/SpectreAgent.kt")
                .readText()
        assertTrue(
            source.contains("invokeSyntheticFactory") || source.contains("synthetic"),
            "SpectreAgent must construct RobotDriver.synthetic rather than the real-OS constructor",
        )

        val driver =
            SpectreAgent.createCoordinatedRobotDriverOrLegacyFallback(
                javaClass.classLoader,
                SyntheticRecordingRobotDriver::class.java,
            )

        val constructed = assertIs<SyntheticRecordingRobotDriver>(driver)
        assertEquals(InputLeasePolicy.Required, constructed.policy)
        assertTrue(constructed.viaSynthetic, "attach must call Companion.synthetic(policy)")
    }

    @Test
    fun `legacy target without InputLeasePolicy falls back to no-argument driver`() {
        val legacyLoader =
            object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String): Class<*> {
                    if (name == "dev.sebastiano.spectre.core.InputLeasePolicy") {
                        throw ClassNotFoundException(name)
                    }
                    return super.loadClass(name)
                }
            }

        val driver =
            SpectreAgent.createCoordinatedRobotDriverOrLegacyFallback(
                legacyLoader,
                LegacyRobotDriver::class.java,
            )

        assertIs<LegacyRobotDriver>(driver)
    }

    /**
     * The #472 default pin.
     *
     * `Required` is the only policy that never degrades, and that is deliberate: coordination is
     * the mutual exclusion that stops two Spectre processes interleaving real input on one desktop
     * (#446, #447, #449, #460). Moving this default would trade a loud failure for a silent
     * correctness risk, so it is asserted here rather than left to review.
     */
    @Test
    fun `the attach path coordinates by default`() {
        val driver =
            SpectreAgent.createCoordinatedRobotDriverOrLegacyFallback(
                javaClass.classLoader,
                PolicyRecordingRobotDriver::class.java,
            )

        assertEquals(InputLeasePolicy.Required, assertIs<PolicyRecordingRobotDriver>(driver).policy)
    }

    @Test
    fun `an explicitly required attach coordinates`() {
        val driver =
            SpectreAgent.createCoordinatedRobotDriverOrLegacyFallback(
                javaClass.classLoader,
                PolicyRecordingRobotDriver::class.java,
                AttachInputCoordination.Required,
            )

        assertEquals(InputLeasePolicy.Required, assertIs<PolicyRecordingRobotDriver>(driver).policy)
    }

    /**
     * The escape hatch lands on [InputLeasePolicy.Off], not [InputLeasePolicy.Auto].
     *
     * `Auto` degrades for exactly two error codes (`COORDINATOR_PROVIDER_MISSING`,
     * `COORDINATOR_SESSION_UNAVAILABLE`) and a wedged coordinator surfaces as neither — the
     * launching provider's startup timeout becomes an `IOException`, which the production
     * coordinator reports as `COORDINATOR_IO`. `Auto` would therefore hard-fail the very case this
     * hatch exists for, so opting out has to mean opting out.
     */
    @Test
    fun `the escape hatch turns coordination off rather than making it best-effort`() {
        val driver =
            SpectreAgent.createCoordinatedRobotDriverOrLegacyFallback(
                javaClass.classLoader,
                PolicyRecordingRobotDriver::class.java,
                AttachInputCoordination.Disabled,
            )

        assertEquals(InputLeasePolicy.Off, assertIs<PolicyRecordingRobotDriver>(driver).policy)
    }

    @Test
    fun `a legacy target ignores the escape hatch instead of failing the attach`() {
        val legacyLoader =
            object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String): Class<*> {
                    if (name == "dev.sebastiano.spectre.core.InputLeasePolicy") {
                        throw ClassNotFoundException(name)
                    }
                    return super.loadClass(name)
                }
            }

        val driver =
            SpectreAgent.createCoordinatedRobotDriverOrLegacyFallback(
                legacyLoader,
                LegacyRobotDriver::class.java,
                AttachInputCoordination.Disabled,
            )

        assertIs<LegacyRobotDriver>(driver)
    }

    private class LegacyRobotDriver

    /** Stands in for `RobotDriver`'s `(InputLeasePolicy)` constructor and records what it got. */
    private class PolicyRecordingRobotDriver(val policy: InputLeasePolicy)

    /**
     * Stands in for current-core `RobotDriver.Companion.synthetic(policy)` so the attach path
     * cannot silently keep constructing the real-OS `(InputLeasePolicy)` constructor.
     */
    private class SyntheticRecordingRobotDriver(
        val policy: InputLeasePolicy,
        val viaSynthetic: Boolean,
    ) {
        constructor(policy: InputLeasePolicy) : this(policy, viaSynthetic = false)

        companion object {
            fun synthetic(policy: InputLeasePolicy): SyntheticRecordingRobotDriver =
                SyntheticRecordingRobotDriver(policy, viaSynthetic = true)
        }
    }
}

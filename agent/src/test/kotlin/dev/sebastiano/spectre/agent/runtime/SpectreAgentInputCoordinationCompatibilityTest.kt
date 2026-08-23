@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import kotlin.test.Test
import kotlin.test.assertIs

class SpectreAgentInputCoordinationCompatibilityTest {

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

    private class LegacyRobotDriver
}

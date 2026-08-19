@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `FrameLimits` reads `SPECTRE_MAX_FRAME_BYTES` in its own static initializer, so a JVM that starts
 * with the variable set takes a code path no in-process test can reach — the class is already
 * initialized by then. A field ordering slip there once made the class fail to load on exactly the
 * machines that configured the override, so this spawns a real JVM to cover it.
 */
class FrameLimitsEnvTest {

    @Test
    fun `an unset environment resolves the default budget`() {
        assertEquals("budget=$DEFAULT_MAX_FRAME_BYTES", runProbe(override = null))
    }

    @Test
    fun `a JVM started with the override resolves and applies it`() {
        assertEquals("budget=${128 * 1024 * 1024}", runProbe(override = "128MiB"))
    }

    @Test
    fun `an unparseable override still boots on the default`() {
        assertEquals("budget=$DEFAULT_MAX_FRAME_BYTES", runProbe(override = "banana"))
    }

    @Test
    fun `an override above the ceiling is clamped rather than refused`() {
        assertEquals("budget=$MAX_FRAME_BYTES_CEILING", runProbe(override = "1G"))
    }

    private fun runProbe(override: String?): String {
        val java = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val builder =
            ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), PROBE_MAIN_CLASS)
        builder.redirectErrorStream(true)
        if (override == null) {
            builder.environment().remove(FrameLimits.ENV_VAR)
        } else {
            builder.environment()[FrameLimits.ENV_VAR] = override
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(
            process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "probe JVM did not exit in time",
        )
        assertEquals(0, process.exitValue(), "probe JVM failed to start FrameLimits:\n$output")
        return output.trim().lines().last()
    }

    private companion object {
        const val PROBE_MAIN_CLASS: String =
            "dev.sebastiano.spectre.agent.transport.FrameLimitsEnvProbeKt"
        const val PROBE_TIMEOUT_SECONDS: Long = 60
    }
}

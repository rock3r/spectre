package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.recording.portal.WaylandPortalAccessResult
import dev.sebastiano.spectre.recording.portal.WaylandPortalAccessStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Test runs on Linux Wayland never open the portal dialog by themselves. */
class WaylandPortalPrecheckTest {

    private val properties = mutableMapOf<String, String>()
    private val warnings = mutableListOf<String>()

    @Test
    fun `off Wayland it changes nothing`() {
        run(applicable = false, granted = false)

        assertTrue(properties.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `on Wayland it forbids implicit dialogs`() {
        run(applicable = true, granted = true)

        assertEquals("never", properties[CONSENT_PROMPT_PROPERTY])
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `an explicit setting wins`() {
        properties[CONSENT_PROMPT_PROPERTY] = "allow"

        run(applicable = true, granted = true)

        assertEquals("allow", properties[CONSENT_PROMPT_PROPERTY])
    }

    @Test
    fun `the environment variable also counts as an explicit setting`() {
        run(applicable = true, granted = true, env = mapOf(CONSENT_PROMPT_ENV to "allow"))

        assertTrue(CONSENT_PROMPT_PROPERTY !in properties)
    }

    @Test
    fun `a missing grant is reported once with the fix`() {
        run(applicable = true, granted = false)

        val warning = warnings.single()
        assertTrue(warning.contains("spectre permissions request"), warning)
        assertTrue(warning.contains("fail"), warning)
    }

    private fun run(applicable: Boolean, granted: Boolean, env: Map<String, String> = emptyMap()) {
        WaylandPortalPrecheck.run(
            isApplicable = { applicable },
            preflight = {
                WaylandPortalAccessResult(
                    status =
                        if (granted) WaylandPortalAccessStatus.GRANTED
                        else WaylandPortalAccessStatus.MISSING,
                    guidance = "Run `spectre permissions request` once.",
                )
            },
            getProperty = properties::get,
            setProperty = { key, value -> properties[key] = value },
            getenv = env::get,
            warn = { warnings += it },
        )
    }
}

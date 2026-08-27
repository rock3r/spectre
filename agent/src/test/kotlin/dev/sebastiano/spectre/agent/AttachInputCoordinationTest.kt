@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The truth table for the #472 escape hatch.
 *
 * The point of the hatch is to be *hard to trip over*. Coordination is what stops two Spectre
 * processes driving the same mouse and keyboard, so a value that merely looks affirmative must not
 * turn it off: only the exact word wins, and everything else — unset, blank, `true`, a typo — lands
 * back on [AttachInputCoordination.Required]. Falling back that way is the safe direction, and the
 * failure the user then still gets names the exact spelling, so a typo surfaces rather than hides.
 */
class AttachInputCoordinationTest {

    @Test
    fun `an unset property keeps coordination required`() {
        assertEquals(AttachInputCoordination.Required, AttachInputCoordination.fromProperty(null))
    }

    @Test
    fun `a blank property keeps coordination required`() {
        assertEquals(AttachInputCoordination.Required, AttachInputCoordination.fromProperty(""))
        assertEquals(AttachInputCoordination.Required, AttachInputCoordination.fromProperty("   "))
    }

    @Test
    fun `the exact word disables coordination`() {
        assertEquals(
            AttachInputCoordination.Disabled,
            AttachInputCoordination.fromProperty("disabled"),
        )
    }

    @Test
    fun `case and surrounding whitespace do not change the verdict`() {
        assertEquals(
            AttachInputCoordination.Disabled,
            AttachInputCoordination.fromProperty("  DiSaBlEd  "),
        )
    }

    @Test
    fun `required can be pinned explicitly`() {
        assertEquals(
            AttachInputCoordination.Required,
            AttachInputCoordination.fromProperty("required"),
        )
    }

    @Test
    fun `affirmative-looking values are not the escape hatch`() {
        // A boolean switch would be one stray `-Dsomething=true` away from silently unpolicing the
        // desktop. Requiring the word is the whole point.
        for (value in listOf("true", "1", "yes", "on", "off", "false", "none", "auto")) {
            assertEquals(
                AttachInputCoordination.Required,
                AttachInputCoordination.fromProperty(value),
                "\"$value\" must not disable coordination",
            )
        }
    }

    @Test
    fun `a typo falls back to required rather than disabling`() {
        assertEquals(
            AttachInputCoordination.Required,
            AttachInputCoordination.fromProperty("disable"),
        )
        assertEquals(
            AttachInputCoordination.Required,
            AttachInputCoordination.fromProperty("disabledd"),
        )
    }

    @Test
    fun `the property is namespaced with the other agent switches`() {
        assertEquals(
            "dev.sebastiano.spectre.agent.inputCoordination",
            AttachInputCoordination.PROPERTY,
        )
    }

    @Test
    fun `the documented value is the one the parser accepts`() {
        assertSame(
            AttachInputCoordination.Disabled,
            AttachInputCoordination.fromProperty(AttachInputCoordination.DISABLE_VALUE),
        )
    }
}

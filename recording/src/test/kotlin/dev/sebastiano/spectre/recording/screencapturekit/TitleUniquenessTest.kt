package dev.sebastiano.spectre.recording.screencapturekit

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The helper matches `--title-contains` as a substring, so a title is only safe to pass as-is when
 * no other showing window's title contains it.
 */
class TitleUniquenessTest {

    @Test
    fun `a title no other window contains does not need a discriminator`() {
        assertFalse(titleNeedsDiscriminator("Editor", otherTitles = listOf("Settings", null, "")))
    }

    @Test
    fun `a blank or null title needs a discriminator`() {
        assertTrue(titleNeedsDiscriminator(null, otherTitles = emptyList()))
        assertTrue(titleNeedsDiscriminator("  ", otherTitles = emptyList()))
    }

    @Test
    fun `an exact duplicate title needs a discriminator`() {
        assertTrue(titleNeedsDiscriminator("Editor", otherTitles = listOf("Editor")))
    }

    @Test
    fun `a title contained in another window title needs a discriminator`() {
        assertTrue(titleNeedsDiscriminator("Editor", otherTitles = listOf("Editor Settings")))
    }

    @Test
    fun `a title that only contains another window title does not need a discriminator`() {
        assertFalse(titleNeedsDiscriminator("Editor Settings", otherTitles = listOf("Editor")))
    }
}

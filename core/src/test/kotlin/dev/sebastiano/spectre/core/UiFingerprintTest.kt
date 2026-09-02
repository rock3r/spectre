package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the one part of [uiFingerprint] that can be tested without a live Compose tree: how a node's
 * text / content-description list collapses to a hash.
 *
 * `WaitForIdleTest` stubs the fingerprint function wholesale, so nothing there can see a change in
 * how the fingerprint is *built*. That gap let a separator go missing during a refactor: without
 * one, `["ab", "c"]` and `["a", "bc"]` hash the same, and `waitForIdle` reads a real semantics
 * change as a stable UI and returns early. Caught by Codex review on #489.
 */
class UiFingerprintTest {

    @Test
    fun `lists with the same characters but different segmentation hash differently`() {
        assertNotEquals(
            fingerprintHashOf(listOf("ab", "c")),
            fingerprintHashOf(listOf("a", "bc")),
            "a separator-less join makes these collide, which hides a real semantics change",
        )
    }

    @Test
    fun `an element boundary is distinguishable from no boundary at all`() {
        assertNotEquals(fingerprintHashOf(listOf("a", "b")), fingerprintHashOf(listOf("ab")))
    }

    @Test
    fun `equal lists hash equally`() {
        assertEquals(fingerprintHashOf(listOf("Loading…")), fingerprintHashOf(listOf("Loading…")))
        assertEquals(fingerprintHashOf(emptyList()), fingerprintHashOf(emptyList()))
    }

    @Test
    fun `a changed element changes the hash`() {
        assertNotEquals(
            fingerprintHashOf(listOf("Count: 1")),
            fingerprintHashOf(listOf("Count: 2")),
        )
    }
}

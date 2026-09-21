package dev.sebastiano.spectre.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plaintext loopback identity for the HTTP 426 hatch.
 *
 * Windows reverse-DNS of 127.0.0.1 is the computer name, so a check that trusts that name rejects
 * the in-process CIO harness (`allowInsecureLoopback`) with `426 Upgrade Required`.
 */
class SpectreLoopbackPeerTest {

    @Test
    fun `scoped ipv6 loopback is loopback`() {
        assertTrue(isLoopbackHost("::1%12"))
        assertTrue(isLoopbackHost("[::1%12]"))
        assertTrue(isLoopbackHost("[::1]"))
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("::ffff:127.0.0.1"))
        assertFalse(isLoopbackHost("10.0.0.1"))
        assertFalse(isLoopbackHost("MATTONE"))
        assertFalse(isLoopbackHost("cafe"))
    }

    @Test
    fun `plaintext peer uses the tcp address and ignores reverse dns`() {
        assertTrue(isLoopbackHost(loopbackCheckInput("127.0.0.1", "MATTONE")))
        assertFalse(isLoopbackHost(loopbackCheckInput("10.0.0.8", "localhost")))
        assertFalse(isLoopbackHost(loopbackCheckInput("", "localhost")))
        assertFalse(isLoopbackHost(loopbackCheckInput("unknown", "127.0.0.1")))
    }
}

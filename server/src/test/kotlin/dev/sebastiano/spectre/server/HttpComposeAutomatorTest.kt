@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpComposeAutomatorTest {

    @Test
    fun `normaliseBaseUrl produces the same canonical URL for every basePath variant`() {
        val expected = "https://localhost:9274/spectre"
        assertEquals(expected, HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "/spectre"))
        assertEquals(expected, HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "spectre"))
        assertEquals(
            expected,
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "/spectre/"),
        )
        assertEquals(expected, HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "spectre/"))
    }

    @Test
    fun `normaliseBaseUrl handles a multi-segment basePath`() {
        assertEquals(
            "http://localhost:9274/api/v1/spectre",
            HttpComposeAutomator.normaliseBaseUrl(
                "localhost",
                9274,
                "api/v1/spectre",
                allowInsecureLoopback = true,
            ),
        )
        assertEquals(
            "https://localhost:9274/api/v1/spectre",
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "/api/v1/spectre/"),
        )
    }

    @Test
    fun `normaliseBaseUrl handles an empty basePath`() {
        assertEquals(
            "https://localhost:9274",
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, ""),
        )
        assertEquals(
            "https://localhost:9274",
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "/"),
        )
    }

    @Test
    fun `plaintext escape hatch accepts loopback only`() {
        assertEquals(
            "http://127.0.0.1:9274/spectre",
            HttpComposeAutomator.normaliseBaseUrl(
                host = "127.0.0.1",
                port = 9274,
                basePath = "/spectre",
                allowInsecureLoopback = true,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            HttpComposeAutomator.normaliseBaseUrl(
                host = "example.com",
                port = 9274,
                basePath = "/spectre",
                allowInsecureLoopback = true,
            )
        }
    }
}

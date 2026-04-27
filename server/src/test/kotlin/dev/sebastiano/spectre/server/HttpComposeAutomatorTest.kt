package dev.sebastiano.spectre.server

import kotlin.test.Test
import kotlin.test.assertEquals

class HttpComposeAutomatorTest {

    @Test
    fun `normaliseBaseUrl produces the same canonical URL for every basePath variant`() {
        val expected = "http://localhost:9274/spectre"
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
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "api/v1/spectre"),
        )
        assertEquals(
            "http://localhost:9274/api/v1/spectre",
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "/api/v1/spectre/"),
        )
    }

    @Test
    fun `normaliseBaseUrl handles an empty basePath`() {
        assertEquals(
            "http://localhost:9274",
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, ""),
        )
        assertEquals(
            "http://localhost:9274",
            HttpComposeAutomator.normaliseBaseUrl("localhost", 9274, "/"),
        )
    }
}

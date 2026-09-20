@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpExposureSecurityTest {

    private fun automator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless(), discoverWindows = false)

    @Test
    fun `every non-preflight route requires the deployment bearer`() = testApplication {
        application { installSpectreRoutes(automator(), testHttpSecurity()) }

        val missing = client.get("/spectre/windows")
        val missingPost = client.post("/spectre/click")
        val wrong =
            client.get("/spectre/windows") {
                header(HttpHeaders.Authorization, "Bearer wrong-token")
            }
        val valid =
            client.get("/spectre/windows") {
                header(HttpHeaders.Authorization, "Bearer $TEST_HTTP_TOKEN")
            }

        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(HttpStatusCode.Unauthorized, missingPost.status)
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(HttpStatusCode.OK, valid.status)
        assertFalse(wrong.bodyAsText().contains("wrong-token"))
        assertFalse(wrong.bodyAsText().contains(TEST_HTTP_TOKEN))
    }

    @Test
    fun `plaintext is rejected unless the loopback escape hatch is enabled`() = testApplication {
        application {
            installSpectreRoutes(automator(), SpectreHttpSecurity(bearerToken = TEST_HTTP_TOKEN))
        }

        val response =
            client.get("/spectre/windows") {
                header(HttpHeaders.Authorization, "Bearer $TEST_HTTP_TOKEN")
            }

        assertEquals(HttpStatusCode.UpgradeRequired, response.status)
    }

    @Test
    fun `CORS fails closed when no browser origin is configured`() = testApplication {
        application { installSpectreRoutes(automator(), testHttpSecurity()) }

        val response =
            client.get("/spectre/windows") {
                header(HttpHeaders.Origin, "https://runner.example")
                header(HttpHeaders.Authorization, "Bearer $TEST_HTTP_TOKEN")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(null, response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `configured origin is echoed exactly on authenticated response`() = testApplication {
        val origin = "https://runner.example"
        application {
            installSpectreRoutes(automator(), testHttpSecurity(allowedOrigins = setOf(origin)))
        }

        val response =
            client.get("/spectre/windows") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.Authorization, "Bearer $TEST_HTTP_TOKEN")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(response.headers.getAll(HttpHeaders.Vary).orEmpty().contains(HttpHeaders.Origin))
    }

    @Test
    fun `allowed CORS preflight does not require a bearer`() = testApplication {
        val origin = "https://runner.example"
        application {
            installSpectreRoutes(automator(), testHttpSecurity(allowedOrigins = setOf(origin)))
        }

        val response =
            client.options("/spectre/click") {
                header(HttpHeaders.Origin, origin)
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, "authorization, content-type")
            }

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertTrue(
            response.headers[HttpHeaders.AccessControlAllowHeaders]
                .orEmpty()
                .contains(HttpHeaders.Authorization)
        )
    }

    @Test
    fun `disallowed CORS preflight is rejected without echoing bearer`() = testApplication {
        application { installSpectreRoutes(automator(), testHttpSecurity()) }

        val response =
            client.options("/spectre/click") {
                header(HttpHeaders.Origin, "https://attacker.example")
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.Authorization)
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertFalse(response.bodyAsText().contains(TEST_HTTP_TOKEN))
    }

    @Test
    fun `security configuration never renders its bearer`() {
        val security = testHttpSecurity()

        assertTrue(security.acceptsAuthorization("Bearer $TEST_HTTP_TOKEN"))
        assertFalse(security.acceptsAuthorization("Bearer ${TEST_HTTP_TOKEN}x"))
        assertFalse(security.toString().contains(TEST_HTTP_TOKEN))
    }

    @Test
    fun `security configuration rejects weak bearers and wildcard origins`() {
        assertFailsWith<IllegalArgumentException> { SpectreHttpSecurity(bearerToken = "too-short") }
        assertFailsWith<IllegalArgumentException> {
            SpectreHttpSecurity(bearerToken = TEST_HTTP_TOKEN, allowedOrigins = setOf("*"))
        }
    }
}

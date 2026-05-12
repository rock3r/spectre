package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.server.dto.ClickRequest
import dev.sebastiano.spectre.server.dto.NodesResponse
import dev.sebastiano.spectre.server.dto.WindowsResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install as serverInstall
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test for the HTTP transport.
 *
 * Drives the real route handlers through Ktor's `testApplication` (no real network sockets) with a
 * real headless `ComposeAutomator` backing them. The automator has no Compose surfaces in this
 * environment, so the assertions are about the request/response *envelope* contract — list shapes,
 * status codes, body decoding — not about live UI behaviour.
 *
 * Endpoints that drive real input or capture (`typeText`, `screenshot`) are intentionally not
 * covered here: the headless driver throws on those, and asserting that the route surfaces a 5xx is
 * not particularly informative — the in-process automator tests cover the success path.
 */
class SpectreServerRoundTripTest {

    private fun headlessAutomator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())

    @Test
    fun `windows endpoint returns an empty list for an empty automator`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/spectre/windows").body<WindowsResponse>()

        assertEquals(emptyList(), response.windows)
    }

    @Test
    fun `nodes endpoint returns an empty list when no windows are tracked`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/spectre/nodes").body<NodesResponse>()

        assertEquals(emptyList(), response.nodes)
    }

    @Test
    fun `nodes endpoint accepts a testTag query parameter`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/spectre/nodes?testTag=Send").body<NodesResponse>()

        assertEquals(emptyList(), response.nodes)
    }

    @Test
    fun `click against an unknown node key returns 404`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/spectre/click") {
                contentType(ContentType.Application.Json)
                setBody(ClickRequest(nodeKey = "nonexistent:0:1"))
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
        // R5/F5c: the 404 body deliberately does NOT echo the caller-supplied node key. The
        // no-echo contract is pinned in `HttpNegativeContractTest`; here we just confirm the
        // status code and the absence of the key in the round-trip path.
        assertTrue(!response.bodyAsText().contains("nonexistent:0:1"))
    }

    @Test
    fun `click against a malformed node key returns 400`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response =
            client.post("/spectre/click") {
                contentType(ContentType.Application.Json)
                setBody(ClickRequest(nodeKey = "not-a-valid-key"))
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Malformed node key"))
    }

    @Test
    fun `routes can be mounted at a custom base path`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), basePath = "/api/v1/spectre") }
        val client = createClient { install(ContentNegotiation) { json() } }

        val response = client.get("/api/v1/spectre/windows").body<WindowsResponse>()
        assertEquals(emptyList(), response.windows)
    }

    @Test
    fun `installSpectreRoutes is safe when ContentNegotiation is already installed`() =
        testApplication {
            application {
                // Host configures ContentNegotiation for its own routes. The route installer
                // must reuse it rather than calling install() again, which would throw a
                // duplicate-plugin exception at startup.
                serverInstall(ServerContentNegotiation) { json() }
                installSpectreRoutes(headlessAutomator())
            }
            val client = createClient { install(ContentNegotiation) { json() } }

            val response = client.get("/spectre/windows").body<WindowsResponse>()
            assertEquals(emptyList(), response.windows)
        }
}

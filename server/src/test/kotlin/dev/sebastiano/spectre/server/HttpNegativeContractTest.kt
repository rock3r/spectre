package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.server.dto.ScreenshotResponse
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install as serverInstall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get as serverGet
import io.ktor.server.routing.post as serverPost
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Negative-path coverage for the HTTP transport (R4):
 *
 * - **Server-side** (routes registered via [installSpectreRoutes]): malformed request bodies and
 *   wrong content types map to actionable 4xx responses. The handlers wrap `call.receive` in narrow
 *   try/catch blocks so the client gets a curated message naming the request type instead of Ktor's
 *   default `BadRequest` empty-bodied response.
 * - **Client-side** ([HttpComposeAutomator]): server 5xx on `click` surfaces as
 *   `IllegalStateException` (existing `check {}` guard), and `screenshot` decoding a response with
 *   non-image bytes surfaces as the existing `checkNotNull` failure. Other client methods
 *   (`windows`, `allNodes`, `findByTestTag`, `typeText`) go directly into `body<T>()` on the
 *   response — adding curated handling for them would expand the transport surface, which R4
 *   explicitly declines (tracked as R-future, post-#96).
 */
class HttpNegativeContractTest {

    private fun headlessAutomator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless())

    // --- Server-side decode failures -----------------------------------------------------

    @Test
    fun `POST click with empty body returns 415`() = testApplication {
        // Empty body + `Content-Type: application/json` reaches Ktor's content-negotiation
        // precheck before our `receiveOrRespond400` wrapper. The precheck rejects the
        // zero-byte body with 415 because there's nothing for the JSON converter to read —
        // we pin that here so a future Ktor upgrade that changed it to 400 would notice us.
        application { installSpectreRoutes(headlessAutomator()) }
        val response =
            postRaw("/spectre/click", body = "", contentType = ContentType.Application.Json)
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST click with invalid JSON returns 400 with a useful message`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val response =
            postRaw("/spectre/click", body = "not json", contentType = ContentType.Application.Json)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertBodyMentions(response, "ClickRequest")
    }

    @Test
    fun `POST click with missing nodeKey returns 400 with a useful message`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val response =
            postRaw("/spectre/click", body = "{}", contentType = ContentType.Application.Json)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertBodyMentions(response, "ClickRequest")
    }

    @Test
    fun `POST click with wrong Content-Type returns 415`() = testApplication {
        application { installSpectreRoutes(headlessAutomator()) }
        val response =
            postRaw("/spectre/click", body = "anything", contentType = ContentType.Text.Plain)
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST typeText with empty body returns 415`() = testApplication {
        // Symmetry confirmation that the same Ktor content-negotiation precheck applies to
        // /typeText — empty body with `application/json` is rejected as 415 before our
        // `receiveOrRespond400` wrapper runs. Pinned for the same reason as the /click case.
        application { installSpectreRoutes(headlessAutomator()) }
        val response =
            postRaw("/spectre/typeText", body = "", contentType = ContentType.Application.Json)
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `POST typeText with invalid JSON returns 400 with a useful message`() = testApplication {
        // Symmetry confirmation that the `receiveOrRespond400` wrapper is wired on /typeText too,
        // and that the curated body names the request type.
        application { installSpectreRoutes(headlessAutomator()) }
        val response =
            postRaw(
                "/spectre/typeText",
                body = "not json",
                contentType = ContentType.Application.Json,
            )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertBodyMentions(response, "TypeTextRequest")
    }

    // --- Client-side error surfacing -----------------------------------------------------
    //
    // HttpComposeAutomator owns its CIO HttpClient internally (no constructor seam to inject
    // one), so client-side negative tests boot a real CIO server bound to port 0 — same
    // pattern as HttpComposeAutomatorE2ETest.kt — and point the public `ComposeAutomator.http`
    // factory at it.

    @Test
    fun `click against a 500 response surfaces an IllegalStateException`() {
        val server = startEphemeralServer {
            routing {
                serverPost("/spectre/click") {
                    call.respond(HttpStatusCode.InternalServerError, "synthetic server failure")
                }
            }
        }
        try {
            val error =
                ComposeAutomator.http(host = "127.0.0.1", port = server.port).use {
                    assertFailsWith<IllegalStateException> { runBlocking { it.click("ignored") } }
                }
            assertTrue(
                error.message?.contains("500") == true,
                "expected status=500 to appear in the error, got: ${error.message}",
            )
        } finally {
            server.server.stop(gracePeriodMillis = 0L, timeoutMillis = 0L)
        }
    }

    @Test
    fun `screenshot against a non-image response raises a clear decode error`() {
        val server = startEphemeralServer {
            serverInstall(ServerContentNegotiation) { json() }
            routing {
                serverGet("/spectre/screenshot") {
                    call.respond(
                        ScreenshotResponse(
                            width = 0,
                            height = 0,
                            pngBase64 =
                                Base64.getEncoder()
                                    .encodeToString("definitely not a png".toByteArray()),
                        )
                    )
                }
            }
        }
        try {
            val error =
                ComposeAutomator.http(host = "127.0.0.1", port = server.port).use {
                    assertFailsWith<IllegalStateException> { runBlocking { it.screenshot() } }
                }
            assertEquals("Server returned an image we could not decode", error.message)
        } finally {
            server.server.stop(gracePeriodMillis = 0L, timeoutMillis = 0L)
        }
    }

    private fun startEphemeralServer(module: Application.() -> Unit): EphemeralServer {
        val server = embeddedServer(CIO, port = 0, module = module).start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        return EphemeralServer(server, port)
    }

    private data class EphemeralServer(val server: EmbeddedServer<*, *>, val port: Int)

    private suspend fun ApplicationTestBuilder.postRaw(
        path: String,
        body: String,
        contentType: ContentType,
    ): HttpResponse =
        client.post(path) {
            this.contentType(contentType)
            setBody(body)
        }

    private suspend fun assertBodyMentions(response: HttpResponse, needle: String) {
        val text = response.bodyAsText()
        assertTrue(
            text.contains(needle, ignoreCase = true),
            "expected response body to mention '$needle', got: $text",
        )
    }
}

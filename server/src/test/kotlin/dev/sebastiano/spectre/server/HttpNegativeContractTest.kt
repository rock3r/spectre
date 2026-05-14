@file:OptIn(ExperimentalSpectreHttpApi::class)

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
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless(), discoverWindows = false)

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
        // The body is deliberately an attacker-shaped payload: if the server ever regresses to
        // echoing the underlying decoder message back to the caller (as it did pre-R5), this
        // assertion fails. The test pins the no-echo behaviour without depending on Ktor's
        // exact serializer prose — only the curated type name is part of the contract.
        application { installSpectreRoutes(headlessAutomator()) }
        val response =
            postRaw(
                "/spectre/click",
                body = "<script>alert(1)</script>",
                contentType = ContentType.Application.Json,
            )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertBodyMentions(response, "ClickRequest")
        assertBodyDoesNotContain(response, "<script>")
    }

    @Test
    fun `POST click with a malformed node key returns 400 without echoing the key`() =
        testApplication {
            // Reaches `SpectreServer.kt`'s malformed-key 400 branch, not the schema-decode 400
            // branch: the JSON parses cleanly, so `receiveOrRespond400` succeeds, and only then
            // does `NodeKey.parse` throw `IllegalArgumentException("Invalid NodeKey: $key")`.
            // Pre-R5 that exception message was interpolated into the response body, echoing the
            // attacker key. Pin that no-echo behaviour here.
            application { installSpectreRoutes(headlessAutomator()) }
            val response =
                postRaw(
                    "/spectre/click",
                    body = "{\"nodeKey\": \"<script>alert(1)</script>\"}",
                    contentType = ContentType.Application.Json,
                )
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertBodyDoesNotContain(response, "<script>")
        }

    @Test
    fun `POST click with a well-formed but unknown node key returns 404 without echoing the key`() =
        testApplication {
            // Reaches the 404 no-matching-node branch (not the malformed-key 400 branch): the
            // key parses as surfaceId=`<script>alert(1)</script>`, ownerIndex=0, nodeId=1, so
            // `NodeKey.parse` succeeds and the handler falls through to "no node with this key".
            // Pre-R5 that branch interpolated `request.nodeKey` into the response body.
            application { installSpectreRoutes(headlessAutomator()) }
            val response =
                postRaw(
                    "/spectre/click",
                    body = "{\"nodeKey\": \"<script>alert(1)</script>:0:1\"}",
                    contentType = ContentType.Application.Json,
                )
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertBodyDoesNotContain(response, "<script>")
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
    fun `click against a 500 response surfaces an IllegalStateException without echoing body`() {
        // The peer reply body is an attacker-shaped payload. Pre-R5 the client's `check {}`
        // lambda interpolated `response.bodyAsText()` into the thrown message, reflecting
        // arbitrary peer content into logs / test output. The fix retains the status code in
        // the message but drops the body. Pin both halves: status present, body absent.
        val server = startEphemeralServer {
            routing {
                serverPost("/spectre/click") {
                    call.respond(HttpStatusCode.InternalServerError, "<script>alert(1)</script>")
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
            assertTrue(
                error.message?.contains("<script>") != true,
                "expected peer body NOT to be echoed in the error, got: ${error.message}",
            )
        } finally {
            server.server.stop(gracePeriodMillis = 0L, timeoutMillis = 0L)
        }
    }

    @Test
    fun `typeText against a 500 response surfaces an IllegalStateException without echoing body`() {
        // Symmetry test for `HttpComposeAutomator.typeText`. Same `check {}` pattern as click,
        // same body-echo concern, same fix shape.
        val server = startEphemeralServer {
            routing {
                serverPost("/spectre/typeText") {
                    call.respond(HttpStatusCode.InternalServerError, "<script>alert(1)</script>")
                }
            }
        }
        try {
            val error =
                ComposeAutomator.http(host = "127.0.0.1", port = server.port).use {
                    assertFailsWith<IllegalStateException> {
                        runBlocking { it.typeText("ignored") }
                    }
                }
            assertTrue(
                error.message?.contains("500") == true,
                "expected status=500 to appear in the error, got: ${error.message}",
            )
            assertTrue(
                error.message?.contains("<script>") != true,
                "expected peer body NOT to be echoed in the error, got: ${error.message}",
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

    private suspend fun assertBodyDoesNotContain(response: HttpResponse, needle: String) {
        val text = response.bodyAsText()
        assertTrue(
            !text.contains(needle, ignoreCase = true),
            "expected response body NOT to contain '$needle', got: $text",
        )
    }
}

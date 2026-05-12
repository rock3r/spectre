@file:OptIn(InternalSpectreApi::class, ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.NodeKey
import dev.sebastiano.spectre.server.dto.ClickRequest
import dev.sebastiano.spectre.server.dto.NodesResponse
import dev.sebastiano.spectre.server.dto.ScreenshotResponse
import dev.sebastiano.spectre.server.dto.TypeTextRequest
import dev.sebastiano.spectre.server.dto.WindowsResponse
import dev.sebastiano.spectre.server.dto.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Mounts the Spectre HTTP transport on this Ktor [Application], backed by the supplied in-process
 * [automator]. All routes live under [basePath] (default `/spectre`).
 *
 * The route surface is intentionally a subset of the public automator API — windows, nodes by tag,
 * click, typeText, screenshot — enough to drive cross-JVM smoke tests. Advanced features (idling
 * resources, withTracing, waitForVisualIdle) are in-process only because they either require live
 * JVM objects (Tracer, IdlingResource) or stateful long-poll semantics that are out of scope for
 * the experimental transport. See [the stability policy](https://spectre.sebastiano.dev/STABILITY/)
 * for the API tier definitions.
 *
 * ## Trust boundary
 *
 * The HTTP transport is **experimental** and intended for trusted local / test environments only:
 *
 * - **Unauthenticated.** Every route is open to any caller that can reach the bound port. There are
 *   no tokens, headers, or origin checks.
 * - **Plaintext.** Communication is HTTP, not HTTPS. There is no TLS support.
 * - **Privileged side effects.** `click` and `typeText` drive real OS input; `screenshot` captures
 *   pixels visible to the host JVM. Anything that can reach this server can do all of the above.
 * - **Binding is the host application's responsibility.** This function does not start a server —
 *   pick an engine and bind to `127.0.0.1`. Do not expose the routes to a network interface.
 * - **Authentication, authorization, and TLS** are tracked for a separately reviewed future design
 *   (#96).
 *
 * See [the cross-JVM guide](https://spectre.sebastiano.dev/guide/cross-jvm/) and
 * [the security notes](https://spectre.sebastiano.dev/SECURITY/) for the published exposure model
 * and risk register.
 *
 * ## ContentNegotiation contract
 *
 * The Spectre routes exchange JSON DTOs, so a JSON-capable [ContentNegotiation] plugin must be
 * present on the application by the time the routes serve traffic.
 *
 * - If [ContentNegotiation] is **not** installed, this function installs it with the kotlinx JSON
 *   converter automatically.
 * - If [ContentNegotiation] **is** already installed (because the host application configured it
 *   for its own routes), this function leaves it alone. The host is responsible for ensuring the
 *   existing configuration includes a JSON converter — Ktor's plugin model does not let us merge
 *   converters into an already-installed plugin, and re-installing would throw a duplicate-plugin
 *   exception. Without JSON support the routes will fail at request time with a 415/500.
 */
@ExperimentalSpectreHttpApi
public fun Application.installSpectreRoutes(
    automator: ComposeAutomator,
    basePath: String = "/spectre",
) {
    if (pluginOrNull(ContentNegotiation) == null) {
        install(ContentNegotiation) { json() }
    }
    routing { route(basePath) { spectreRoutes(automator) } }
}

private fun Route.spectreRoutes(automator: ComposeAutomator) {
    get("/windows") {
        automator.refreshWindows()
        val response =
            WindowsResponse(
                windows = automator.windows.mapIndexed { index, window -> window.toDto(index) }
            )
        call.respond(response)
    }

    get("/nodes") {
        // Query handlers always refresh first: in-process callers control when to refresh, but
        // remote callers expect a request to reflect current state without an explicit prelude.
        automator.refreshWindows()
        val testTag = call.request.queryParameters["testTag"]
        val nodes =
            if (testTag != null) {
                automator.findByTestTag(testTag)
            } else {
                automator.allNodes()
            }
        call.respond(NodesResponse(nodes = nodes.map { it.toDto() }))
    }

    post("/click") {
        automator.refreshWindows()
        val request = receiveOrRespond400<ClickRequest>(call, "ClickRequest") ?: return@post
        // NodeKey.parse throws on malformed input — surface that as a client error (400)
        // rather than letting it bubble up as a generic 500. The body must NOT echo the
        // attacker-supplied key (R5/F5b): `NodeKey.parse` raises `Invalid NodeKey: $key`
        // and pre-R5 we interpolated that message verbatim.
        val key =
            try {
                NodeKey.parse(request.nodeKey)
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, "Malformed node key")
                return@post
            }
        val node = automator.allNodes().firstOrNull { it.key == key }
        if (node == null) {
            // R5/F5c: do NOT echo `request.nodeKey` here — caller-controlled content must
            // not be reflected in the response body.
            call.respond(HttpStatusCode.NotFound, "No matching node")
            return@post
        }
        automator.click(node)
        call.respond(HttpStatusCode.NoContent)
    }

    post("/typeText") {
        val request = receiveOrRespond400<TypeTextRequest>(call, "TypeTextRequest") ?: return@post
        automator.typeText(request.text)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/screenshot") {
        val image = automator.screenshot()
        call.respond(image.toScreenshotResponse())
    }
}

internal fun BufferedImage.toScreenshotResponse(): ScreenshotResponse {
    val bytes =
        ByteArrayOutputStream().use { stream ->
            ImageIO.write(this, "png", stream)
            stream.toByteArray()
        }
    return ScreenshotResponse(
        pngBase64 = Base64.getEncoder().encodeToString(bytes),
        width = width,
        height = height,
    )
}

/**
 * Narrow decode-error mapping for `call.receive<T>()` (R4): Ktor's default response when
 * kotlinx-serialization fails to decode a request body (invalid JSON, missing required field) is
 * `400 Bad Request` with an empty body. That's a poor user experience — the client gets a 4xx but
 * no clue what request shape was expected. Wrap each `receive<T>()` so the response carries the
 * request type name; the type name is what the negative-contract tests pin.
 *
 * Scope is intentionally narrow: only `BadRequestException` (which `ContentTransformation` raises
 * on decode failures) is caught. Everything else propagates. Wrong Content-Type still surfaces as
 * `415 Unsupported Media Type` via Ktor's content-negotiation precheck — Ktor doesn't even get to
 * `receive<T>()` in that case.
 *
 * R5/F5a: the response body deliberately omits the underlying exception message. Decode exceptions
 * from kotlinx-serialization can quote fragments of the request body, which would reflect
 * attacker-controlled content back to the caller. The curated request-type name alone is enough for
 * diagnostics; serializer prose belongs in server-side logs, not on the wire.
 */
private suspend inline fun <reified T : Any> receiveOrRespond400(
    call: io.ktor.server.application.ApplicationCall,
    requestTypeName: String,
): T? =
    try {
        call.receive<T>()
    } catch (_: io.ktor.server.plugins.BadRequestException) {
        call.respond(HttpStatusCode.BadRequest, "Could not decode $requestTypeName")
        null
    }

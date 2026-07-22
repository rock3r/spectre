@file:OptIn(InternalSpectreApi::class, ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.NodeKey
import dev.sebastiano.spectre.server.dto.ClickRequest
import dev.sebastiano.spectre.server.dto.DoubleClickRequest
import dev.sebastiano.spectre.server.dto.LongClickRequest
import dev.sebastiano.spectre.server.dto.NodesResponse
import dev.sebastiano.spectre.server.dto.PressKeyRequest
import dev.sebastiano.spectre.server.dto.ScreenshotResponse
import dev.sebastiano.spectre.server.dto.ScrollWheelRequest
import dev.sebastiano.spectre.server.dto.SwipeRequest
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
import kotlin.time.Duration.Companion.milliseconds

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
        automator.refreshWindows()
        val nodes = selectNodes(automator, call.request.queryParameters)
        if (nodes == null) {
            call.respond(
                SpectreErrorCategory.httpStatus(SpectreErrorCategory.InvalidSelector),
                SpectreErrorCategory.InvalidSelector.wireName,
            )
            return@get
        }
        call.respond(NodesResponse(nodes = nodes.map { it.toDto() }))
    }

    inputRoutes(automator)

    get("/screenshot") {
        try {
            val image = automator.screenshot()
            call.respond(image.toScreenshotResponse())
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (_: IllegalStateException) {
            // Screen Recording TCC / Robot refusal → inputRejected (409).
            call.respond(
                SpectreErrorCategory.httpStatus(SpectreErrorCategory.InputRejected),
                SpectreErrorCategory.InputRejected.wireName,
            )
        }
    }
}

/** Input verbs (#203) — kept out of [spectreRoutes] for length/complexity budgets. */
private fun Route.inputRoutes(automator: ComposeAutomator) {
    post("/click") {
        automator.refreshWindows()
        val request = receiveOrRespond400<ClickRequest>(call, "ClickRequest") ?: return@post
        val node = resolveNodeOrRespond(call, automator, request.nodeKey) ?: return@post
        respondInputVoid(call) { automator.click(node) }
    }

    post("/doubleClick") {
        automator.refreshWindows()
        val request =
            receiveOrRespond400<DoubleClickRequest>(call, "DoubleClickRequest") ?: return@post
        val node = resolveNodeOrRespond(call, automator, request.nodeKey) ?: return@post
        respondInputVoid(call) { automator.doubleClick(node) }
    }

    post("/longClick") {
        automator.refreshWindows()
        val request = receiveOrRespond400<LongClickRequest>(call, "LongClickRequest") ?: return@post
        if (request.holdForMs <= 0L) {
            call.respond(
                SpectreErrorCategory.httpStatus(SpectreErrorCategory.InvalidSelector),
                SpectreErrorCategory.InvalidSelector.wireName,
            )
            return@post
        }
        val node = resolveNodeOrRespond(call, automator, request.nodeKey) ?: return@post
        respondInputVoid(call) { automator.longClick(node, request.holdForMs.milliseconds) }
    }

    post("/swipe") {
        automator.refreshWindows()
        val request = receiveOrRespond400<SwipeRequest>(call, "SwipeRequest") ?: return@post
        handleSwipePost(call, automator, request)
    }

    post("/scrollWheel") {
        automator.refreshWindows()
        val request =
            receiveOrRespond400<ScrollWheelRequest>(call, "ScrollWheelRequest") ?: return@post
        val node = resolveNodeOrRespond(call, automator, request.nodeKey) ?: return@post
        respondInputVoid(call) { automator.scrollWheel(node, request.wheelClicks) }
    }

    post("/pressKey") {
        val request = receiveOrRespond400<PressKeyRequest>(call, "PressKeyRequest") ?: return@post
        if (request.keyCode <= 0) {
            respondInvalidSelector(call)
            return@post
        }
        respondInputVoid(call) { automator.pressKey(request.keyCode, request.modifiers) }
    }

    post("/typeText") {
        val request = receiveOrRespond400<TypeTextRequest>(call, "TypeTextRequest") ?: return@post
        respondInputVoid(call) { automator.typeText(request.text) }
    }
}

private suspend fun handleSwipePost(
    call: io.ktor.server.application.ApplicationCall,
    automator: ComposeAutomator,
    request: SwipeRequest,
) {
    if (request.steps <= 0 || request.durationMs <= 0L) {
        respondInvalidSelector(call)
        return
    }
    val nodeMode = request.fromNodeKey != null || request.toNodeKey != null
    val coordMode =
        request.startX != null ||
            request.startY != null ||
            request.endX != null ||
            request.endY != null
    if (nodeMode == coordMode) {
        respondInvalidSelector(call)
        return
    }
    if (nodeMode) handleNodeSwipePost(call, automator, request)
    else handleCoordSwipePost(call, automator, request)
}

private suspend fun handleNodeSwipePost(
    call: io.ktor.server.application.ApplicationCall,
    automator: ComposeAutomator,
    request: SwipeRequest,
) {
    val fromKey = request.fromNodeKey
    val toKey = request.toNodeKey
    if (fromKey == null || toKey == null) {
        respondInvalidSelector(call)
        return
    }
    val from = resolveNodeOrRespond(call, automator, fromKey) ?: return
    val to = resolveNodeOrRespond(call, automator, toKey) ?: return
    respondInputVoid(call) {
        automator.swipe(from, to, request.steps, request.durationMs.milliseconds)
    }
}

private suspend fun handleCoordSwipePost(
    call: io.ktor.server.application.ApplicationCall,
    automator: ComposeAutomator,
    request: SwipeRequest,
) {
    val startX = request.startX
    val startY = request.startY
    val endX = request.endX
    val endY = request.endY
    if (startX == null || startY == null || endX == null || endY == null) {
        respondInvalidSelector(call)
        return
    }
    respondInputVoid(call) {
        automator.swipe(startX, startY, endX, endY, request.steps, request.durationMs.milliseconds)
    }
}

private suspend fun respondInvalidSelector(call: io.ktor.server.application.ApplicationCall) {
    call.respond(
        SpectreErrorCategory.httpStatus(SpectreErrorCategory.InvalidSelector),
        SpectreErrorCategory.InvalidSelector.wireName,
    )
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
 * Looks up a node by wire key. Responds with invalidSelector (malformed key) or nodeNotFound and
 * returns null when the caller should stop. Does not echo the raw key in the body (R5).
 */
private suspend fun resolveNodeOrRespond(
    call: io.ktor.server.application.ApplicationCall,
    automator: ComposeAutomator,
    nodeKey: String,
): AutomatorNode? {
    val key =
        try {
            NodeKey.parse(nodeKey)
        } catch (_: IllegalArgumentException) {
            call.respond(
                SpectreErrorCategory.httpStatus(SpectreErrorCategory.InvalidSelector),
                SpectreErrorCategory.InvalidSelector.wireName,
            )
            return null
        }
    val node = automator.allNodes().firstOrNull { it.key == key }
    if (node == null) {
        call.respond(
            SpectreErrorCategory.httpStatus(SpectreErrorCategory.NodeNotFound),
            SpectreErrorCategory.NodeNotFound.wireName,
        )
        return null
    }
    return node
}

/** Runs a real-input suspend block; maps Robot/TCC refusals to inputRejected. */
private suspend fun respondInputVoid(
    call: io.ktor.server.application.ApplicationCall,
    block: suspend () -> Unit,
) {
    try {
        block()
        call.respond(HttpStatusCode.NoContent)
    } catch (ex: kotlinx.coroutines.CancellationException) {
        throw ex
    } catch (_: IllegalStateException) {
        call.respond(
            SpectreErrorCategory.httpStatus(SpectreErrorCategory.InputRejected),
            SpectreErrorCategory.InputRejected.wireName,
        )
    }
}

/**
 * Resolves `/nodes` selectors (#202). Returns null for invalidSelector cases: more than one
 * selector query param, whitespace-only text, blank contentDescription/role, or an unknown role
 * name.
 *
 * Empty text (`text=`) is allowed so exact match can target empty [editableText] fields, matching
 * in-process `findByText("")`.
 */
private fun selectNodes(
    automator: ComposeAutomator,
    params: io.ktor.http.Parameters,
): List<dev.sebastiano.spectre.core.AutomatorNode>? {
    val testTag = params["testTag"]
    val text = params["text"]
    val contentDescription = params["contentDescription"]
    val role = params["role"]
    if (listOfNotNull(testTag, text, contentDescription, role).size > 1) return null
    return when {
        testTag != null -> automator.findByTestTag(testTag)
        text != null -> {
            // Whitespace-only (but not empty) is almost never intentional.
            if (text.isNotEmpty() && text.isBlank()) return null
            // Absent `exact` defaults to true; present-but-non-boolean is invalidSelector
            // (do not silently coerce `FALSE` / `yes` into the default).
            val exactParam = params["exact"]
            val exact =
                if (exactParam == null) {
                    true
                } else {
                    exactParam.toBooleanStrictOrNull() ?: return null
                }
            automator.findByText(text, exact = exact)
        }
        contentDescription != null -> {
            if (contentDescription.isBlank()) return null
            automator.findByContentDescription(contentDescription)
        }
        // Role is a Compose value class; match by toString() name ("Button", …).
        role != null -> {
            if (role.isBlank() || role !in KNOWN_ROLE_WIRE_NAMES) return null
            automator.allNodes().filter { it.role?.toString() == role }
        }
        else -> automator.allNodes()
    }
}

/**
 * Compose [androidx.compose.ui.semantics.Role.toString] names. Kept local to the server module so
 * HTTP and agent agree without a shared compile-time Role dependency in agent. [Role.ValuePicker]
 * stringifies as `"Picker"`.
 */
private val KNOWN_ROLE_WIRE_NAMES: Set<String> =
    setOf(
        "Button",
        "Checkbox",
        "Switch",
        "RadioButton",
        "Tab",
        "Image",
        "DropdownList",
        "Picker",
        "Carousel",
    )

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

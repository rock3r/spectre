@file:OptIn(InternalSpectreApi::class, ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.server.dto.NodeResponse
import dev.sebastiano.spectre.server.dto.NodesResponse
import dev.sebastiano.spectre.server.dto.PrintTreeResponse
import dev.sebastiano.spectre.server.dto.TextMatchTypeDto
import dev.sebastiano.spectre.server.dto.TextQueryDto
import dev.sebastiano.spectre.server.dto.TreeResponse
import dev.sebastiano.spectre.server.dto.toDto
import dev.sebastiano.spectre.server.dto.toModel
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.awt.image.BufferedImage

/** Selectors, tree snapshot, printTree, and screenshot (#202, #96). */
internal fun Route.spectreQueryRoutes(automator: ComposeAutomator) {
    get("/nodes") {
        automator.refreshWindows()
        val nodes = selectNodes(automator, call.request.queryParameters)
        if (nodes == null) {
            respondInvalidSelector(call)
            return@get
        }
        call.respond(NodesResponse(nodes = nodes.map { it.toDto() }))
    }

    get("/node") {
        automator.refreshWindows()
        val selectorCount =
            listOfNotNull(
                    call.request.queryParameters["testTag"],
                    call.request.queryParameters["text"],
                    call.request.queryParameters["contentDescription"],
                    call.request.queryParameters["role"],
                )
                .size
        if (selectorCount != 1) {
            respondInvalidSelector(call)
            return@get
        }
        val nodes = selectNodes(automator, call.request.queryParameters)
        if (nodes == null) {
            respondInvalidSelector(call)
            return@get
        }
        call.respond(NodeResponse(node = nodes.firstOrNull()?.toDto()))
    }

    get("/tree") {
        val indexParam = call.request.queryParameters["windowIndex"]
        val tree = automator.tree()
        if (indexParam == null) {
            call.respond(tree.toDto())
            return@get
        }
        val index = indexParam.toIntOrNull()
        if (index == null || index < 0 || index >= tree.windows().size) {
            respondInvalidSelector(call)
            return@get
        }
        call.respond(TreeResponse(windows = listOf(tree.window(index).toDto())))
    }

    get("/printTree") { call.respond(PrintTreeResponse(dump = automator.printTree())) }

    get("/screenshot") {
        val nodeKey = call.request.queryParameters["nodeKey"]
        if (nodeKey != null) {
            automator.refreshWindows()
            val node = resolveNodeOrRespond(call, automator, nodeKey) ?: return@get
            respondScreenshot(call) { automator.screenshot(node) }
        } else {
            respondScreenshot(call) { automator.screenshot() }
        }
    }
}

private suspend fun respondScreenshot(call: ApplicationCall, capture: () -> BufferedImage) {
    try {
        call.respond(capture().toScreenshotResponse())
    } catch (ex: kotlinx.coroutines.CancellationException) {
        throw ex
    } catch (ex: UnsupportedOperationException) {
        respondScreenshotFailure(call, ex)
    } catch (ex: IllegalStateException) {
        respondScreenshotFailure(call, ex)
    }
}

private suspend fun respondScreenshotFailure(call: ApplicationCall, ex: Exception) {
    val category = mapScreenshotFailure(ex)
    call.respond(SpectreErrorCategory.httpStatus(category), category.wireName)
}

/**
 * Native capture backends throw [IllegalStateException] or [UnsupportedOperationException] (missing
 * `:recording` artifact, disabled/headless backend, non-Frame host). Both are `inputRejected`
 * (409); [CancellationException] is not a capture failure.
 */
internal fun mapScreenshotFailure(ex: Throwable): SpectreErrorCategory {
    if (ex is kotlinx.coroutines.CancellationException) throw ex
    return when (ex) {
        is UnsupportedOperationException,
        is IllegalStateException -> SpectreErrorCategory.InputRejected
        else -> throw ex
    }
}

/**
 * Resolves `/nodes` and `/node` selectors (#202, #96). Returns null for invalidSelector cases: more
 * than one selector query param, whitespace-only text, blank contentDescription/role, an unknown
 * role name, structured `TextQuery` combined with `exact`, unknown `matchType`, or non-boolean
 * `ignoreCase`.
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
    val matchType = params["matchType"]
    val ignoreCaseParam = params["ignoreCase"]
    if (listOfNotNull(testTag, text, contentDescription, role).size > 1) return null
    if ((matchType != null || ignoreCaseParam != null) && text == null) return null
    return when {
        testTag != null -> automator.findByTestTag(testTag)
        text != null -> selectNodesByText(automator, text, params)
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

private fun selectNodesByText(
    automator: ComposeAutomator,
    text: String,
    params: io.ktor.http.Parameters,
): List<dev.sebastiano.spectre.core.AutomatorNode>? {
    // Whitespace-only (but not empty) is almost never intentional.
    if (text.isNotEmpty() && text.isBlank()) return null
    val exactParam = params["exact"]
    val matchType = params["matchType"]
    val ignoreCaseParam = params["ignoreCase"]
    if (matchType != null || ignoreCaseParam != null) {
        if (exactParam != null) return null
        val query = parseTextQuery(text, matchType, ignoreCaseParam) ?: return null
        return automator.findByText(query.toModel())
    }
    // Absent `exact` defaults to true; present-but-non-boolean is invalidSelector
    // (do not silently coerce `FALSE` / `yes` into the default).
    val exact = if (exactParam == null) true else exactParam.toBooleanStrictOrNull() ?: return null
    return automator.findByText(text, exact = exact)
}

private fun parseTextQuery(
    text: String,
    matchType: String?,
    ignoreCaseParam: String?,
): TextQueryDto? {
    val type =
        when (matchType) {
            null -> TextMatchTypeDto.Exact
            "Exact" -> TextMatchTypeDto.Exact
            "Substring" -> TextMatchTypeDto.Substring
            else -> return null
        }
    val ignoreCase =
        if (ignoreCaseParam == null) {
            false
        } else {
            ignoreCaseParam.toBooleanStrictOrNull() ?: return null
        }
    return TextQueryDto(value = text, matchType = type, ignoreCase = ignoreCase)
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

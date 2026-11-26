@file:OptIn(
    ExperimentalSpectreHttpApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package dev.sebastiano.spectre.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

private val strictHttpJson: Json = Json { ignoreUnknownKeys = false }

/** Strict production decoder for the HTTP input DTOs, including duplicate-name detection. */
internal suspend fun <T : Any> receiveOrRespond400(
    call: ApplicationCall,
    requestTypeName: String,
    serializer: KSerializer<T>,
): T? {
    if (!call.request.contentType().match(ContentType.Application.Json)) {
        throw UnsupportedMediaTypeException(call.request.contentType())
    }

    // Keep transport failures and cancellation outside the decode-error boundary.
    val raw = call.receiveText()
    return try {
        requireNoDuplicateJsonNames(raw)
        val element = strictHttpJson.parseToJsonElement(raw).jsonObject
        validateJsonPrimitiveKinds(element, serializer)
        strictHttpJson.decodeFromJsonElement(serializer, element)
    } catch (_: SerializationException) {
        call.respond(HttpStatusCode.BadRequest, "Could not decode $requestTypeName")
        null
    } catch (_: IllegalArgumentException) {
        call.respond(HttpStatusCode.BadRequest, "Could not decode $requestTypeName")
        null
    }
}

/** Checks decoded root-object member names without mistaking string contents for properties. */
private fun requireNoDuplicateJsonNames(raw: String) {
    val names = mutableSetOf<String>()
    var index = 0
    var depth = 0
    while (index < raw.length) {
        when (raw[index]) {
            '{' -> {
                depth++
                index++
            }
            '}' -> {
                depth--
                index++
            }
            '"' -> {
                val start = index
                index = jsonStringEnd(raw, index)
                var next = index
                while (next < raw.length && raw[next].isWhitespace()) next++
                if (depth == 1 && next < raw.length && raw[next] == ':') {
                    val name = strictHttpJson.decodeFromString<String>(raw.substring(start, index))
                    require(names.add(name)) { "Duplicate JSON property" }
                }
            }
            else -> index++
        }
    }
}

private fun jsonStringEnd(raw: String, openingQuote: Int): Int {
    var index = openingQuote + 1
    var escaped = false
    while (index < raw.length) {
        val character = raw[index++]
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '"' -> return index
        }
    }
    return index
}

private fun <T : Any> validateJsonPrimitiveKinds(
    objectValue: JsonObject,
    serializer: KSerializer<T>,
) {
    val descriptor = serializer.descriptor
    val known = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
    require(objectValue.keys.all { it in known }) { "Unknown JSON property" }
    objectValue.forEach { (name, value) ->
        val child = descriptor.getElementDescriptor(known.getValue(name))
        if (value is JsonNull) {
            require(child.isNullable) { "$name must not be null" }
            return@forEach
        }
        val primitive =
            value as? JsonPrimitive ?: throw IllegalArgumentException("$name must be a primitive")
        when (child.kind) {
            kotlinx.serialization.descriptors.PrimitiveKind.STRING -> require(primitive.isString)
            kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN ->
                require(!primitive.isString && primitive.content.toBooleanStrictOrNull() != null)
            kotlinx.serialization.descriptors.PrimitiveKind.INT ->
                require(!primitive.isString && primitive.content.toIntOrNull() != null)
            kotlinx.serialization.descriptors.PrimitiveKind.LONG ->
                require(!primitive.isString && primitive.content.toLongOrNull() != null)
            else -> error("Unsupported request property kind for $name")
        }
    }
}

internal suspend fun ApplicationCall.rejectQueryParameters(): Boolean {
    if (hasOnlyQueryParameters(emptySet())) return true
    respondInvalidSelector(this)
    return false
}

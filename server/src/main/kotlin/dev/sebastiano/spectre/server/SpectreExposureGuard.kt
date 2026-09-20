@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

internal fun Route.installExposureGuard(security: SpectreHttpSecurity) {
    install(SpectreExposureGuard) { this.security = security }
}

private class SpectreExposureGuardConfig {
    lateinit var security: SpectreHttpSecurity
}

private val SpectreExposureGuard =
    createRouteScopedPlugin(
        name = "SpectreHttpExposureGuard",
        createConfiguration = ::SpectreExposureGuardConfig,
    ) {
        val security = pluginConfig.security
        onCall { call ->
            if (!call.usesAllowedTransport(security)) {
                call.respond(HttpStatusCode.UpgradeRequired, "HTTPS required")
                return@onCall
            }

            val originValues = call.request.headers.getAll(HttpHeaders.Origin)
            val requestedMethodValues =
                call.request.headers.getAll(HttpHeaders.AccessControlRequestMethod)
            if ((originValues?.size ?: 0) > 1 || (requestedMethodValues?.size ?: 0) > 1) {
                call.respond(HttpStatusCode.Forbidden, "CORS request rejected")
                return@onCall
            }
            val origin = originValues?.singleOrNull()
            val requestedMethod = requestedMethodValues?.singleOrNull()
            val isPreflight =
                call.request.local.method == HttpMethod.Options &&
                    origin != null &&
                    requestedMethod != null

            if (isPreflight) {
                call.respondToPreflight(security, origin, requestedMethod)
                return@onCall
            }

            if (origin != null && !call.allowCorsOrigin(security, origin)) {
                call.respond(HttpStatusCode.Forbidden, "Origin not allowed")
                return@onCall
            }

            val authorizationValues = call.request.headers.getAll(HttpHeaders.Authorization)
            val authorized =
                authorizationValues?.singleOrNull()?.let(security::acceptsAuthorization) == true
            if (!authorized) {
                call.response.headers.append(
                    HttpHeaders.WWWAuthenticate,
                    """Bearer realm="Spectre"""",
                )
                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
            }
        }
    }

private fun ApplicationCall.usesAllowedTransport(security: SpectreHttpSecurity): Boolean =
    request.origin.scheme.equals("https", ignoreCase = true) ||
        (security.allowInsecureLoopback && isLoopbackHost(request.local.remoteHost))

private suspend fun ApplicationCall.respondToPreflight(
    security: SpectreHttpSecurity,
    origin: String,
    requestedMethod: String,
) {
    val methodAllowed =
        requestedMethod == HttpMethod.Get.value || requestedMethod == HttpMethod.Post.value
    val requestedHeaders =
        request.headers[HttpHeaders.AccessControlRequestHeaders]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    val headersAllowed = requestedHeaders.all {
        it.equals(HttpHeaders.Authorization, ignoreCase = true) ||
            it.equals(HttpHeaders.ContentType, ignoreCase = true)
    }
    if (!methodAllowed || !headersAllowed || !allowCorsOrigin(security, origin)) {
        respond(HttpStatusCode.Forbidden, "CORS preflight rejected")
        return
    }

    response.headers.append(
        HttpHeaders.AccessControlAllowMethods,
        "${HttpMethod.Get.value}, ${HttpMethod.Post.value}",
    )
    response.headers.append(
        HttpHeaders.AccessControlAllowHeaders,
        "${HttpHeaders.Authorization}, ${HttpHeaders.ContentType}",
    )
    appendVary(HttpHeaders.AccessControlRequestMethod, HttpHeaders.AccessControlRequestHeaders)
    respond(HttpStatusCode.NoContent)
}

private fun ApplicationCall.allowCorsOrigin(
    security: SpectreHttpSecurity,
    origin: String,
): Boolean {
    if (!security.allowsOrigin(origin)) return false
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, origin)
    response.headers.append(HttpHeaders.AccessControlAllowCredentials, "true")
    appendVary(HttpHeaders.Origin)
    return true
}

private fun ApplicationCall.appendVary(vararg names: String) {
    names.forEach { response.headers.append(HttpHeaders.Vary, it) }
}

@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

internal const val TEST_HTTP_TOKEN: String = "test-deployment-token-0123456789"

internal fun testHttpSecurity(allowedOrigins: Set<String> = emptySet()): SpectreHttpSecurity =
    SpectreHttpSecurity(
        bearerToken = TEST_HTTP_TOKEN,
        allowedOrigins = allowedOrigins,
        allowInsecureLoopback = true,
    )

internal fun HttpClientConfig<*>.configureTestBearer() {
    defaultRequest { header(HttpHeaders.Authorization, "Bearer $TEST_HTTP_TOKEN") }
}

internal fun HttpRequestBuilder.testBearer() {
    header(HttpHeaders.Authorization, "Bearer $TEST_HTTP_TOKEN")
}

internal suspend fun HttpClient.testGet(url: String): HttpResponse = get(url) { testBearer() }

internal suspend fun HttpClient.testPost(
    url: String,
    block: HttpRequestBuilder.() -> Unit,
): HttpResponse =
    post(url) {
        testBearer()
        block()
    }

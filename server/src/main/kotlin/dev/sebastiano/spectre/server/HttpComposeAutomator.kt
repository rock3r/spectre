package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.server.dto.ClickRequest
import dev.sebastiano.spectre.server.dto.NodeSnapshotDto
import dev.sebastiano.spectre.server.dto.NodesResponse
import dev.sebastiano.spectre.server.dto.ScreenshotResponse
import dev.sebastiano.spectre.server.dto.TypeTextRequest
import dev.sebastiano.spectre.server.dto.WindowSummaryDto
import dev.sebastiano.spectre.server.dto.WindowsResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Cross-JVM client for a `ComposeAutomator` running behind [installSpectreRoutes].
 *
 * Created via the companion extension `ComposeAutomator.http(host, port)` (see
 * [HttpFactory.kt][http]). The v1 client surface mirrors the in-process automator's most-used
 * queries and actions; advanced features (idling resources, `withTracing`, `waitForVisualIdle`)
 * remain in-process only and are documented as such.
 *
 * The instance owns its [HttpClient] and must be `close()`d to release pooled connections. Use `use
 * { ... }` from `kotlin.AutoCloseable` for scoped lifecycles.
 */
class HttpComposeAutomator internal constructor(private val baseUrl: String) : AutoCloseable {

    // HttpClient(CIO) — the engine *factory* form — makes the client own the engine, so
    // close() shuts down both the client and the underlying CIO engine (its connection pool
    // and selector threads). Passing a pre-built engine instance via HttpClient(engine) would
    // leak the engine on close, since Ktor only auto-closes engines it constructed itself.
    private val client: HttpClient = HttpClient(CIO) { install(ContentNegotiation) { json() } }

    suspend fun windows(): List<WindowSummaryDto> =
        client.get("$baseUrl/windows").body<WindowsResponse>().windows

    suspend fun allNodes(): List<NodeSnapshotDto> =
        client.get("$baseUrl/nodes").body<NodesResponse>().nodes

    suspend fun findByTestTag(tag: String): List<NodeSnapshotDto> =
        client.get("$baseUrl/nodes") { parameter("testTag", tag) }.body<NodesResponse>().nodes

    suspend fun click(nodeKey: String) {
        val response =
            client.post("$baseUrl/click") {
                contentType(ContentType.Application.Json)
                setBody(ClickRequest(nodeKey = nodeKey))
            }
        check(response.status.isSuccess()) {
            "click failed: ${response.status} ${response.bodyAsText()}"
        }
    }

    suspend fun typeText(text: String) {
        val response =
            client.post("$baseUrl/typeText") {
                contentType(ContentType.Application.Json)
                setBody(TypeTextRequest(text = text))
            }
        check(response.status.isSuccess()) {
            "typeText failed: ${response.status} ${response.bodyAsText()}"
        }
    }

    suspend fun screenshot(): BufferedImage {
        val response = client.get("$baseUrl/screenshot").body<ScreenshotResponse>()
        val bytes = Base64.getDecoder().decode(response.pngBase64)
        return checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) {
            "Server returned an image we could not decode"
        }
    }

    override fun close() {
        client.close()
    }

    companion object {

        /** Default port suggested by the gist's HTTP example. */
        const val DEFAULT_PORT: Int = 9274

        internal fun create(host: String, port: Int, basePath: String): HttpComposeAutomator =
            HttpComposeAutomator(baseUrl = "http://$host:$port$basePath")
    }
}

/**
 * Companion extension matching the gist's intended public surface `ComposeAutomator.http(host,
 * port)`. Returns an [HttpComposeAutomator] connected to the remote process; the caller is
 * responsible for closing it.
 */
fun ComposeAutomator.Companion.http(
    host: String = "localhost",
    port: Int = HttpComposeAutomator.DEFAULT_PORT,
    basePath: String = "/spectre",
): HttpComposeAutomator = HttpComposeAutomator.create(host = host, port = port, basePath = basePath)

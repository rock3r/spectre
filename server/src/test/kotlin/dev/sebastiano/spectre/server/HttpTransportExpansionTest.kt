@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Remaining data-only HTTP surface from #96: structured [dev.sebastiano.spectre.core.TextQuery],
 * `findOneBy*`, `clearAndTypeText`, node-targeted screenshot, `tree()` / `tree(windowIndex)`, and
 * `printTree()`. Envelope tests against a headless automator; live UI behaviour stays out of this
 * class.
 */
class HttpTransportExpansionTest {

    private fun headlessAutomator(): ComposeAutomator =
        ComposeAutomator.inProcess(robotDriver = RobotDriver.headless(), discoverWindows = false)

    @Test
    fun `tree endpoint returns an empty windows list for an empty automator`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/tree")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("\"windows\""),
            "expected TreeResponse windows field, got: ${response.bodyAsText()}",
        )
    }

    @Test
    fun `tree windowIndex out of range returns invalidSelector`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/tree?windowIndex=0")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `printTree endpoint returns a dump envelope for an empty automator`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/printTree")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("\"dump\""),
            "expected PrintTreeResponse dump field, got: ${response.bodyAsText()}",
        )
    }

    @Test
    fun `nodes endpoint rejects matchType without text`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/nodes?testTag=Send&matchType=Exact")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `nodes endpoint rejects a non-boolean ignoreCase`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/nodes?text=Submit&matchType=Exact&ignoreCase=maybe")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `nodes endpoint rejects unknown TextQuery matchType`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/nodes?text=Submit&matchType=Regex")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `nodes endpoint rejects combining exact with structured matchType`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/nodes?text=Submit&exact=true&matchType=Exact")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `nodes endpoint accepts structured TextQuery params`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response =
            client.testGet("/spectre/nodes?text=Submit&matchType=Substring&ignoreCase=true")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("\"nodes\""),
            "expected NodesResponse, got: ${response.bodyAsText()}",
        )
    }

    @Test
    fun `node endpoint without a selector returns invalidSelector`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/node")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `node endpoint with two selectors returns invalidSelector`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/node?testTag=Send&text=Submit")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `node endpoint rejects combining exact with structured matchType`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/node?text=Submit&exact=true&matchType=Exact")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `tree windowIndex that is not an integer returns invalidSelector`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/tree?windowIndex=abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `node endpoint returns a null node when unmatched`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/node?testTag=Send")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.bodyAsText().contains("\"node\""),
            "expected NodeResponse, got: ${response.bodyAsText()}",
        )
        assertTrue(
            response.bodyAsText().contains("null"),
            "unmatched findOne should be JSON null, got: ${response.bodyAsText()}",
        )
    }

    @Test
    fun `screenshot with a malformed nodeKey returns invalidSelector`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/screenshot?nodeKey=not-a-valid-key")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(SpectreErrorCategory.InvalidSelector.wireName, response.bodyAsText())
    }

    @Test
    fun `screenshot with an unknown nodeKey returns nodeNotFound`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response = client.testGet("/spectre/screenshot?nodeKey=nonexistent:0:1")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(SpectreErrorCategory.NodeNotFound.wireName, response.bodyAsText())
    }

    @Test
    fun `clearAndTypeText against an unknown node key returns nodeNotFound`() = testApplication {
        application { installSpectreRoutes(headlessAutomator(), testHttpSecurity()) }

        val response =
            client.testPost("/spectre/clearAndTypeText") {
                contentType(ContentType.Application.Json)
                setBody("""{"nodeKey":"nonexistent:0:1","text":"hello"}""")
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(SpectreErrorCategory.NodeNotFound.wireName, response.bodyAsText())
    }
}

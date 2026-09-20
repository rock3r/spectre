@file:OptIn(ExperimentalSpectreHttpApi::class)

package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.TextQuery
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * End-to-end test for the [HttpComposeAutomator] client class.
 *
 * Complements [SpectreServerRoundTripTest] (which exercises the server-side route handlers via
 * Ktor's `testApplication`) by booting a **real** CIO-backed Ktor server on an ephemeral port and
 * driving it through the public client surface. `testApplication` short-circuits the HTTP
 * transport, so the client's URL construction, query-parameter wiring, JSON decode, and
 * `bodyAsText` paths are not validated end-to-end without a real engine.
 *
 * The backing [ComposeAutomator] is headless, so assertions target the client/transport contract
 * (list shapes, status-code translation, body decoding, engine lifecycle) rather than live UI
 * behaviour. Endpoints that drive real input or capture (`click()` happy path, `typeText`,
 * `screenshot`) are intentionally not covered here — driving them requires a live Compose surface,
 * which the testing module's automator rules cover separately.
 */
class HttpComposeAutomatorE2ETest {

    private lateinit var server: EmbeddedServer<*, *>
    private var port: Int = -1

    @BeforeTest
    fun startServer() {
        val automator =
            ComposeAutomator.inProcess(
                robotDriver = RobotDriver.headless(),
                discoverWindows = false,
            )
        server =
            embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    installSpectreRoutes(automator, testHttpSecurity())
                }
                .start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(gracePeriodMillis = 0L, timeoutMillis = 0L)
    }

    private fun client(): HttpComposeAutomator =
        ComposeAutomator.http(security = testHttpSecurity(), host = "127.0.0.1", port = port)

    @Test
    fun `windows() round-trips through a real CIO engine`() = runBlocking {
        client().use { remote -> assertEquals(emptyList(), remote.windows()) }
    }

    @Test
    fun `allNodes() round-trips through a real CIO engine`() = runBlocking {
        client().use { remote -> assertEquals(emptyList(), remote.allNodes()) }
    }

    @Test
    fun `findByTestTag() forwards the tag as a query parameter`() = runBlocking {
        // The server distinguishes tagged from untagged queries by the presence of the
        // "testTag" query parameter; if the client wired the parameter under a different
        // name the route would silently fall back to the all-nodes branch and still return
        // an empty list — but the assertion below at least exercises the encode path against
        // a real socket, which the testApplication-based round-trip test does not.
        client().use { remote -> assertEquals(emptyList(), remote.findByTestTag("Send")) }
    }

    @Test
    fun `click() against an unknown node key surfaces the server error`() = runBlocking {
        client().use { remote ->
            val ex =
                assertFailsWith<IllegalStateException> { remote.click(nodeKey = "nonexistent:0:1") }
            val message = checkNotNull(ex.message)
            assertTrue(message.contains("404"), "Expected 404 in error message, got: $message")
            // R5/F5d: client `check {}` no longer interpolates `response.bodyAsText()`, so the
            // node key must not appear in the exception message even when the server's 404
            // body somehow surfaced it. The no-echo contract is pinned by
            // `HttpNegativeContractTest`; here we just confirm the absence on the round-trip.
            assertTrue(
                !message.contains("nonexistent:0:1"),
                "Expected node key NOT to appear in error message, got: $message",
            )
        }
    }

    @Test
    fun `findByText(query) forwards structured TextQuery parameters`() = runBlocking {
        client().use { remote ->
            assertEquals(
                emptyList(),
                remote.findByText(TextQuery.substring("sub", ignoreCase = true)),
            )
        }
    }

    @Test
    fun `findOneByTestTag() returns null when unmatched`() = runBlocking {
        client().use { remote -> assertEquals(null, remote.findOneByTestTag("Send")) }
    }

    @Test
    fun `findOneByText(query) returns null when unmatched`() = runBlocking {
        client().use { remote ->
            assertEquals(null, remote.findOneByText(TextQuery.substring("nope", ignoreCase = true)))
        }
    }

    @Test
    fun `findOneByContentDescription() returns null when unmatched`() = runBlocking {
        client().use { remote -> assertEquals(null, remote.findOneByContentDescription("Send")) }
    }

    @Test
    fun `findOneByRole() returns null when unmatched`() = runBlocking {
        client().use { remote -> assertEquals(null, remote.findOneByRole("Button")) }
    }

    @Test
    fun `findOneByRole() against an unknown role surfaces the server error`() = runBlocking {
        client().use { remote ->
            val ex = assertFailsWith<IllegalStateException> { remote.findOneByRole("Bogus") }
            val message = checkNotNull(ex.message)
            assertTrue(message.contains("400"), "Expected 400 in error message, got: $message")
        }
    }

    @Test
    fun `tree() round-trips an empty snapshot`() = runBlocking {
        client().use { remote -> assertEquals(emptyList(), remote.tree()) }
    }

    @Test
    fun `printTree() round-trips an empty dump`() = runBlocking {
        client().use { remote -> assertEquals("", remote.printTree()) }
    }

    @Test
    fun `tree(windowIndex) against an empty automator surfaces the server error`() = runBlocking {
        client().use { remote ->
            val ex = assertFailsWith<IllegalStateException> { remote.tree(0) }
            val message = checkNotNull(ex.message)
            assertTrue(message.contains("400"), "Expected 400 in error message, got: $message")
        }
    }

    @Test
    fun `clearAndTypeText() against an unknown node key surfaces the server error`() = runBlocking {
        client().use { remote ->
            val ex =
                assertFailsWith<IllegalStateException> {
                    remote.clearAndTypeText(nodeKey = "nonexistent:0:1", text = "hello")
                }
            val message = checkNotNull(ex.message)
            assertTrue(message.contains("404"), "Expected 404 in error message, got: $message")
            assertTrue(
                !message.contains("nonexistent:0:1"),
                "Expected node key NOT to appear in error message, got: $message",
            )
        }
    }

    @Test
    fun `screenshot(nodeKey) against an unknown node key surfaces the server error`() =
        runBlocking {
            client().use { remote ->
                val ex =
                    assertFailsWith<IllegalStateException> {
                        remote.screenshot(nodeKey = "nonexistent:0:1")
                    }
                val message = checkNotNull(ex.message)
                assertTrue(message.contains("404"), "Expected 404 in error message, got: $message")
            }
        }

    @Test
    fun `close() shuts down the underlying client engine`() {
        val remote = client()
        runBlocking { remote.windows() }
        remote.close()

        // Engine-shutdown proof that doesn't depend on thread enumeration: a post-close
        // request hits the closed CIO engine and fails. A direct thread-leak assertion is
        // not practical here — the in-test CIO server spawns request-handler threads on
        // demand whose names overlap with client-side worker names, so a baseline diff
        // can't reliably tell client-leaked threads apart from server-handler threads that
        // simply haven't been reaped yet.
        assertFails("Calling a method after close() must fail; engine should be shut down") {
            runBlocking { remote.windows() }
        }
    }
}

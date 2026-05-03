package dev.sebastiano.spectre.server

import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.RobotDriver
import dev.sebastiano.spectre.core.SemanticsReader
import dev.sebastiano.spectre.core.WindowTracker
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
 * behaviour. The `click()` happy path is intentionally not covered here — driving a real node
 * requires a live Compose surface, which the testing module's automator rules cover separately.
 */
class HttpComposeAutomatorE2ETest {

    private lateinit var server: EmbeddedServer<*, *>
    private var port: Int = -1

    @BeforeTest
    fun startServer() {
        val automator =
            ComposeAutomator.inProcess(
                windowTracker = WindowTracker(),
                semanticsReader = SemanticsReader(),
                robotDriver = RobotDriver.headless(),
            )
        server =
            embeddedServer(CIO, port = 0) { installSpectreRoutes(automator) }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopServer() {
        server.stop(gracePeriodMillis = 0L, timeoutMillis = 0L)
    }

    private fun client(): HttpComposeAutomator =
        ComposeAutomator.http(host = "127.0.0.1", port = port)

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
            assertTrue(
                message.contains("nonexistent:0:1"),
                "Expected node key in error message, got: $message",
            )
        }
    }

    @Test
    fun `typeText() succeeds against a running server`() = runBlocking {
        // No exception means the client decoded the 204 No Content response correctly. The
        // headless RobotDriver drops the synthetic key events, so we cannot assert on the UI
        // side effect here — that is what the in-process automator tests are for.
        client().use { remote -> remote.typeText(text = "hello") }
    }

    @Test
    fun `screenshot() decodes the base64 PNG envelope into a BufferedImage`() = runBlocking {
        client().use { remote ->
            val image = remote.screenshot()
            // The headless RobotDriver returns a 1x1 placeholder; the assertion is just that
            // the client successfully base64-decoded the wire payload and ImageIO accepted it.
            assertTrue(image.width > 0, "Width should be positive, got ${image.width}")
            assertTrue(image.height > 0, "Height should be positive, got ${image.height}")
        }
    }

    @Test
    fun `close() shuts down the underlying client engine`() {
        val remote = client()
        runBlocking { remote.windows() }
        remote.close()

        // Strongest portable proof that close() shut the engine down: any subsequent request
        // attempt fails because the engine is no longer accepting jobs. Without close(), the
        // CIO selector threads would keep running and this call would simply succeed.
        assertFails("Calling a method after close() must fail; engine should be shut down") {
            runBlocking { remote.windows() }
        }
    }

    @Test
    fun `close() does not leak CIO selector threads`() {
        val baseline = Thread.getAllStackTraces().keys.toSet()

        val remote = client()
        runBlocking { remote.windows() }
        remote.close()

        // Selector threads exit asynchronously on close; poll a bounded window so the
        // assertion stays robust on a busy CI host without blocking forever on a real leak.
        val deadlineNanos = System.nanoTime() + LEAK_POLL_BUDGET_NANOS
        var leaked: List<String> = collectLeakedClientThreads(baseline)
        while (leaked.isNotEmpty() && System.nanoTime() < deadlineNanos) {
            Thread.sleep(LEAK_POLL_INTERVAL_MS)
            leaked = collectLeakedClientThreads(baseline)
        }

        assertTrue(leaked.isEmpty(), "HttpComposeAutomator.close() leaked client threads: $leaked")
    }

    private fun collectLeakedClientThreads(baseline: Set<Thread>): List<String> =
        Thread.getAllStackTraces()
            .keys
            .asSequence()
            .filter { it !in baseline && it.isAlive }
            .map { it.name }
            // The server in @BeforeTest is itself CIO-backed, so any thread that was already
            // present in the baseline snapshot (including server-side selectors) is filtered
            // out. What remains can only be threads spawned during the client lifecycle —
            // exactly what close() is responsible for tearing down.
            .filter { name ->
                CLIENT_THREAD_NAME_HINTS.any { hint -> name.contains(hint, ignoreCase = true) }
            }
            .toList()

    private companion object {
        private const val LEAK_POLL_BUDGET_NANOS: Long = 5_000_000_000L
        private const val LEAK_POLL_INTERVAL_MS: Long = 50L

        // CIO's client engine names its workers around "selector" / "i/o" / "cio". Matching
        // any of these is broad enough to catch a real leak while ignoring unrelated threads
        // (GC, JIT, finalizer, JUnit) that may legitimately appear after the baseline snapshot.
        private val CLIENT_THREAD_NAME_HINTS: List<String> =
            listOf("selector", "i/o", "cio", "ktor")
    }
}

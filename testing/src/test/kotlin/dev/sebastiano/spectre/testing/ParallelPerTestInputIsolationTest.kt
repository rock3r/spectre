@file:OptIn(
    dev.sebastiano.spectre.core.InternalSpectreApi::class,
    dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class,
)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.AutomatorInputLease
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import dev.sebastiano.spectre.input.server.LocalCoordinatorServer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Release-gate proof that **concurrent** `InputIsolationConfig.perTest()` invocations serialise.
 *
 * [InputIsolationLifecycleTest] proves the whole-test lease spans factory, body, evidence, and
 * teardown, but it drives one extension at a time against a recording (fake) lease, so it would
 * stay green even if two parallel invocations could hold the desktop at once. This test runs
 * several invocations concurrently against a **real** coordinator on a hermetic endpoint and
 * asserts their held intervals never overlap. It backs the `input-coord-junit-pertest` smoke cell
 * (see docs/RELEASE-SMOKE.md).
 *
 * Non-overlap is an invariant rather than a timing race: the lease is mutually exclusive, so a
 * later invocation cannot be granted until the previous one releases. A failure means genuine
 * interleaving, not a slow machine.
 */
class ParallelPerTestInputIsolationTest {

    private val temporaryDirectory: Path = Files.createTempDirectory("spc-par-")
    private val endpoint =
        CoordinatorEndpoint(temporaryDirectory, temporaryDirectory.resolve("coordinator.sock"))
    private val resource = DesktopResourceKey("user:501/parallel-per-test")
    private var server: LocalCoordinatorServer? = null

    @AfterTest
    fun cleanUp() {
        runCatching { server?.close() }
        Files.walk(temporaryDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    @Test
    fun `concurrent per-test invocations never hold the desktop lease at the same time`() {
        server =
            LocalCoordinatorServer(endpoint, idleTimeout = Duration.ofMinutes(1)).also {
                it.start()
            }
        val heldIntervals = CopyOnWriteArrayList<Pair<Long, Long>>()
        val leaseFactory = coordinatorBackedLeaseFactory(heldIntervals)
        val executor = Executors.newFixedThreadPool(INVOCATIONS)
        // Release every invocation at once so they genuinely contend for the same desktop.
        val startLine = CountDownLatch(1)

        try {
            val futures =
                (1..INVOCATIONS).map { invocation ->
                    executor.submit {
                        val extension =
                            ComposeAutomatorExtension(
                                inputIsolation = InputIsolationConfig.perTest(),
                                leaseFactory = leaseFactory,
                                factory = { newHeadlessAutomator() },
                            )
                        val context = contextFor("invocation-$invocation")
                        startLine.await()
                        extension.beforeEach(context)
                        // Stands in for the test body: long enough that a lost lease shows up as
                        // a real overlap at millisecond clock granularity.
                        Thread.sleep(BODY_MILLIS)
                        extension.afterEach(context)
                    }
                }
            startLine.countDown()
            futures.forEach { it.get(INVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(
            INVOCATIONS,
            heldIntervals.size,
            "every concurrent per-test invocation must acquire and release exactly one lease",
        )
        val ordered = heldIntervals.sortedBy { it.first }
        ordered.zipWithNext().forEach { (earlier, later) ->
            assertTrue(
                earlier.second <= later.first,
                "concurrent per-test invocations overlapped on one desktop lease: " +
                    "[${earlier.first}, ${earlier.second}] and [${later.first}, ${later.second}]",
            )
        }
    }

    /**
     * A whole-test lease backed by the real coordinator, recording the window it was held.
     *
     * The release timestamp is taken *before* closing the lease on purpose: the coordinator can
     * grant the next waiter the moment the close is processed, so timestamping afterwards could
     * order this release after the next acquire and fail a correctly serialised run. Recording it
     * first keeps every interval a subset of the true hold, which still exposes real overlap.
     */
    private fun coordinatorBackedLeaseFactory(
        heldIntervals: MutableList<Pair<Long, Long>>
    ): InputTestLeaseFactory = InputTestLeaseFactory { _, ownerLabel ->
        val client = LocalInputCoordinatorClient.connect(endpoint, resource, ownerLabel)
        val lease =
            try {
                client.acquire(Duration.ofSeconds(ACQUIRE_TIMEOUT_SECONDS), "junitPerTest")
            } catch (failure: Throwable) {
                client.close()
                throw failure
            }
        val acquiredAt = System.currentTimeMillis()
        object : AutomatorInputLease {
            override fun bind(automator: ComposeAutomator): AutoCloseable = AutoCloseable {}

            override fun close() {
                val releasedAt = System.currentTimeMillis()
                try {
                    lease.close()
                } finally {
                    client.close()
                    heldIntervals += acquiredAt to releasedAt
                }
            }
        }
    }

    private fun contextFor(displayName: String): RecordingExtensionContext =
        RecordingExtensionContext(
            failure = null,
            testClass = javaClass,
            methodName = fixtureMethod().name,
            uniqueId = "[test:$displayName]",
        )

    private fun fixtureMethod(): java.lang.reflect.Method =
        javaClass.getDeclaredMethod("parallelTestFixture")

    @Suppress("unused") private fun parallelTestFixture(): Unit = Unit

    private companion object {
        const val INVOCATIONS: Int = 4
        const val BODY_MILLIS: Long = 150
        const val ACQUIRE_TIMEOUT_SECONDS: Long = 30
        const val INVOCATION_TIMEOUT_SECONDS: Long = 60
    }
}

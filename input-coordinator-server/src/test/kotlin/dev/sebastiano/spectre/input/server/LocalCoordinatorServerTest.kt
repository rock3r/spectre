@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatedInputLease
import dev.sebastiano.spectre.input.CoordinatorControlResult
import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.InputCoordinatorException
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import dev.sebastiano.spectre.input.LocalInputCoordinatorControl
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalCoordinatorServerTest {

    private val resources = mutableListOf<AutoCloseable>()
    private val temporaryDirectory: Path = Files.createTempDirectory("spc-input-")
    private val endpoint =
        CoordinatorEndpoint(
            directory = temporaryDirectory,
            socketPath = temporaryDirectory.resolve("coordinator.sock"),
        )
    private val resource = DesktopResourceKey("user:501/macos-console")

    @AfterTest
    fun cleanUp() {
        resources.asReversed().forEach { runCatching { it.close() } }
        Files.deleteIfExists(endpoint.socketPath)
        Files.deleteIfExists(temporaryDirectory.resolve("coordinator.lock"))
        Files.deleteIfExists(temporaryDirectory.resolve("recovery.properties.tmp"))
        Files.deleteIfExists(temporaryDirectory.resolve("recovery.properties"))
        Files.deleteIfExists(temporaryDirectory)
    }

    @Test
    fun `two independent clients receive one desktop lease in FIFO order`() {
        startServer()
        val firstClient = client("first")
        val secondClient = client("second")
        val firstLease = firstClient.acquire(Duration.ofSeconds(2), "first click")
        resources += firstLease
        val executor = Executors.newSingleThreadExecutor()
        resources += AutoCloseable { executor.shutdownNow() }

        val secondFuture =
            executor.submit<CoordinatedInputLease> {
                secondClient.acquire(Duration.ofSeconds(2), "second click")
            }
        awaitWaiterCount(1)
        assertFalse(secondFuture.isDone)

        firstLease.close()
        val secondLease = secondFuture.get(2, TimeUnit.SECONDS)
        resources.add(secondLease)
        assertNotEquals(firstLease.token.leaseId, secondLease.token.leaseId)
    }

    @Test
    fun `closing owner session releases its lease and advances FIFO`() {
        startServer()
        val firstClient = client("first")
        val secondClient = client("second")
        firstClient.acquire(Duration.ofSeconds(2), "first click")
        val executor = Executors.newSingleThreadExecutor()
        resources += AutoCloseable { executor.shutdownNow() }
        val secondFuture =
            executor.submit<CoordinatedInputLease> {
                secondClient.acquire(Duration.ofSeconds(2), "second click")
            }
        awaitWaiterCount(1)

        firstClient.close()

        resources.add(secondFuture.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun `interrupting a queued acquisition removes it without disturbing FIFO`() {
        startServer()
        val firstClient = client("first")
        val cancelledClient = client("cancelled")
        val thirdClient = client("third")
        val firstLease = firstClient.acquire(Duration.ofSeconds(2), "first click")
        resources += firstLease
        val executor = Executors.newFixedThreadPool(2)
        resources += AutoCloseable { executor.shutdownNow() }
        val cancelled =
            executor.submit<CoordinatedInputLease> {
                cancelledClient.acquire(Duration.ofSeconds(10), "cancelled click")
            }
        awaitWaiterCount(1)
        val third =
            executor.submit<CoordinatedInputLease> {
                thirdClient.acquire(Duration.ofSeconds(2), "third click")
            }
        awaitWaiterCount(2)

        assertTrue(cancelled.cancel(true))
        awaitWaiterCount(1)
        firstLease.close()

        resources += third.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `cancelling one queued acquire preserves a sibling on the same client session`() {
        startServer()
        val holderClient = client("holder")
        val waitingClient = client("waiting")
        val holder = holderClient.acquire(Duration.ofSeconds(2), "holder click")
        resources += holder
        val executor = Executors.newFixedThreadPool(2)
        resources += AutoCloseable { executor.shutdownNow() }
        val cancelled =
            executor.submit<CoordinatedInputLease> {
                waitingClient.acquire(Duration.ofSeconds(10), "cancelled click")
            }
        awaitWaiterCount(1)
        val sibling =
            executor.submit<CoordinatedInputLease> {
                waitingClient.acquire(Duration.ofSeconds(5), "sibling click")
            }
        awaitWaiterCount(2)

        assertTrue(cancelled.cancel(true))
        awaitWaiterCount(1)
        holder.close()

        resources += sibling.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `coordinator restart changes epoch and old lease checkpoint fails closed`() {
        val firstServer = startServer()
        val firstClient = client("first")
        val oldLease = firstClient.acquire(Duration.ofSeconds(2), "click")
        firstServer.close()
        firstClient.close()
        Files.deleteIfExists(endpoint.socketPath)

        startServer()

        val secondClient = client("second")
        assertNotEquals(firstClient.coordinatorEpoch, secondClient.coordinatorEpoch)
        assertFalse(oldLease.isValid())
    }

    @Test
    fun `successor quarantines a crashed holder until exact-id unsafe recovery`() {
        val firstServer = startServer(recoveryGrace = Duration.ofMinutes(1))
        val firstClient = client("first")
        val oldLease = firstClient.acquire(Duration.ofSeconds(2), "click")
        firstServer.close()
        Files.deleteIfExists(endpoint.socketPath)

        startServer(recoveryGrace = Duration.ofMinutes(1))
        val status =
            assertIs<CoordinatorControlResult.Active>(
                    LocalInputCoordinatorControl(endpoint).status(resource)
                )
                .status
        assertEquals(oldLease.token.leaseId, status.quarantine?.predecessorLeaseId)
        val secondClient = client("second")
        val executor = Executors.newSingleThreadExecutor()
        resources += AutoCloseable { executor.shutdownNow() }
        val waiting =
            executor.submit<CoordinatedInputLease> {
                secondClient.acquire(Duration.ofSeconds(2), "second click")
            }
        awaitWaiterCount(1)
        assertFalse(waiting.isDone)

        val stale =
            assertFailsWith<InputCoordinatorException> {
                LocalInputCoordinatorControl(endpoint)
                    .revoke(resource, "stale", "operator", "predecessor is dead", force = true)
            }
        assertEquals("STALE_LEASE", stale.errorCode)
        val forced =
            LocalInputCoordinatorControl(endpoint)
                .revoke(
                    resource,
                    oldLease.token.leaseId,
                    "operator",
                    "predecessor is dead",
                    force = true,
                )
        assertTrue(forced.unsafeTakeover)
        resources.add(waiting.get(2, TimeUnit.SECONDS))
    }

    @Test
    fun `corrupt recovery ledger quarantines every desktop key until exact force`() {
        Files.writeString(temporaryDirectory.resolve("recovery.properties"), "not-properties")
        startServer(recoveryGrace = Duration.ofMinutes(1))
        val control = LocalInputCoordinatorControl(endpoint)
        val status = assertIs<CoordinatorControlResult.Active>(control.status(resource)).status
        val predecessorId = requireNotNull(status.quarantine?.predecessorLeaseId)
        val waitingClient = client("waiting")
        val executor = Executors.newSingleThreadExecutor()
        resources += AutoCloseable { executor.shutdownNow() }
        val waiting =
            executor.submit<CoordinatedInputLease> {
                waitingClient.acquire(Duration.ofSeconds(2), "click")
            }
        awaitWaiterCount(1)
        assertFalse(waiting.isDone)

        val forced =
            control.revoke(
                resource,
                predecessorId,
                "operator",
                "corrupt recovery record inspected",
                force = true,
            )

        assertTrue(forced.unsafeTakeover)
        resources += waiting.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `acquisition timeout while quarantined returns stable diagnostics`() {
        Files.writeString(temporaryDirectory.resolve("recovery.properties"), "not-properties")
        startServer(recoveryGrace = Duration.ofMinutes(1))

        val failure =
            assertFailsWith<InputCoordinatorException> {
                client("waiting").acquire(Duration.ofMillis(50), "click")
            }

        assertEquals("ACQUIRE_TIMEOUT", failure.errorCode)
        assertTrue(failure.message.orEmpty().contains("quarantine"))
    }

    @Test
    fun `status without active coordinator is observe-only and clean`() {
        val result = LocalInputCoordinatorControl(endpoint).status(resource)

        assertEquals(CoordinatorControlResult.NoActiveCoordinator, result)
        assertFalse(Files.exists(endpoint.socketPath))
    }

    @Test
    fun `coordinator exits after bounded idle period with no holder or waiters`() {
        val server =
            LocalCoordinatorServer(
                endpoint = endpoint,
                heartbeatTimeout = Duration.ofSeconds(5),
                idleTimeout = Duration.ofMillis(50),
            )
        server.start()
        resources += server
        val executor = Executors.newSingleThreadExecutor()
        resources += AutoCloseable { executor.shutdownNow() }

        executor.submit { server.awaitTermination() }.get(2, TimeUnit.SECONDS)

        assertFalse(Files.exists(endpoint.socketPath))
    }

    @Test
    fun `exact-id revoke rejects stale observation and fences the actual holder`() {
        startServer()
        val client = client("first")
        val lease = client.acquire(Duration.ofSeconds(2), "click")
        resources += lease
        val control = LocalInputCoordinatorControl(endpoint)

        val stale =
            assertFailsWith<InputCoordinatorException> {
                control.revoke(resource, "stale-id", "operator", "known stuck")
            }
        assertEquals("STALE_LEASE", stale.errorCode)
        assertTrue(lease.isValid())

        val requested = control.revoke(resource, lease.token.leaseId, "operator", "known stuck")

        assertFalse(requested.unsafeTakeover)
        val fenced = assertFailsWith<InputCoordinatorException> { lease.checkpoint() }
        assertEquals("FENCED", fenced.errorCode)
    }

    @Test
    fun `normal revoke invalidates only its lease and client can acquire again`() {
        startServer(revokeGrace = Duration.ofSeconds(5))
        val firstClient = client("first")
        val secondClient = client("second")
        val firstLease = firstClient.acquire(Duration.ofSeconds(2), "first click")
        resources += firstLease
        val executor = Executors.newSingleThreadExecutor()
        resources += AutoCloseable { executor.shutdownNow() }
        val waiting =
            executor.submit<CoordinatedInputLease> {
                secondClient.acquire(Duration.ofSeconds(5), "second click")
            }
        awaitWaiterCount(1)

        LocalInputCoordinatorControl(endpoint)
            .revoke(resource, firstLease.token.leaseId, "operator", "owner should stop")
        awaitLeaseInvalid(firstLease)
        firstLease.close()

        val secondLease = waiting.get(2, TimeUnit.SECONDS)
        resources += secondLease
        secondLease.close()
        resources += firstClient.acquire(Duration.ofSeconds(2), "client reused after revoke")
    }

    @Test
    fun `closing a reentrant lease preserves heartbeats for the outer lease`() {
        startServer(heartbeatTimeout = Duration.ofMillis(1_500))
        val client = client("reentrant")
        val outer = client.acquire(Duration.ofSeconds(2), "outer transaction")
        resources += outer
        val nested = client.acquire(Duration.ofSeconds(2), "nested focus")

        assertEquals(outer.token, nested.token)
        nested.close()
        Thread.sleep(2_500)

        outer.checkpoint()
        assertTrue(outer.isValid())
    }

    @Test
    fun `explicit force advances FIFO and reports unsafe takeover`() {
        startServer(revokeGrace = Duration.ZERO)
        val firstClient = client("first")
        val secondClient = client("second")
        val firstLease = firstClient.acquire(Duration.ofSeconds(2), "first click")
        resources += firstLease
        val executor = Executors.newSingleThreadExecutor()
        resources += AutoCloseable { executor.shutdownNow() }
        val secondFuture =
            executor.submit<CoordinatedInputLease> {
                secondClient.acquire(Duration.ofSeconds(2), "second click")
            }
        awaitWaiterCount(1)

        val forced =
            LocalInputCoordinatorControl(endpoint)
                .revoke(
                    resourceKey = resource,
                    observedLeaseId = firstLease.token.leaseId,
                    requesterLabel = "operator",
                    reason = "owner is wedged",
                    force = true,
                )

        assertTrue(forced.unsafeTakeover)
        resources.add(secondFuture.get(2, TimeUnit.SECONDS))
    }

    private fun startServer(
        revokeGrace: Duration = Duration.ofSeconds(1),
        recoveryGrace: Duration = Duration.ofSeconds(2),
        heartbeatTimeout: Duration = Duration.ofSeconds(5),
    ): LocalCoordinatorServer {
        val server =
            LocalCoordinatorServer(
                endpoint = endpoint,
                heartbeatTimeout = heartbeatTimeout,
                idleTimeout = Duration.ofMinutes(1),
                revokeGrace = revokeGrace,
                recoveryGrace = recoveryGrace,
            )
        server.start()
        resources += server
        return server
    }

    private fun client(label: String): LocalInputCoordinatorClient {
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint = endpoint,
                resourceKey = resource,
                ownerLabel = label,
            )
        resources += client
        return client
    }

    private fun awaitWaiterCount(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val status = LocalInputCoordinatorControl(endpoint).status(resource)
            val active = status as? CoordinatorControlResult.Active
            if (active?.status?.waiters?.size == expected) return
            Thread.onSpinWait()
        }
        assertTrue(false, "Timed out waiting for $expected queued lease request")
    }

    private fun awaitLeaseInvalid(lease: CoordinatedInputLease) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            if (!lease.isValid()) return
            Thread.sleep(10)
        }
        assertFalse(lease.isValid(), "Timed out waiting for revoked lease heartbeat fencing")
    }
}

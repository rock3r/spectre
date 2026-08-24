@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.CoordinatedInputLease
import dev.sebastiano.spectre.input.CoordinatorControlResult
import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.InputCoordinatorException
import dev.sebastiano.spectre.input.LeaseToken
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import dev.sebastiano.spectre.input.LocalInputCoordinatorControl
import dev.sebastiano.spectre.input.server.LocalCoordinatorServer
import java.awt.Rectangle
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.time.Duration as JavaDuration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class InputLeaseGuardTest {

    @Test
    fun `clear and type acquires once around the whole composite`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator)

        driver.clearAndTypeText("abc")

        assertEquals(listOf("clearAndTypeText"), coordinator.operations)
        assertEquals(1, coordinator.closedLeases)
    }

    @Test
    fun `explicit exclusive scope is reentrant across operations`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator)

        driver.withExclusiveInput(InputLeaseOptions(ownerLabel = "transaction")) {
            driver.click(10, 20)
            driver.typeText("a")
        }

        assertEquals(listOf("exclusiveInput"), coordinator.operations)
        assertEquals(1, coordinator.closedLeases)
    }

    @Test
    fun `blocking focus within an exclusive scope remains reentrant`() {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val completed =
                executor.submit<Boolean> {
                    runBlocking {
                        driver.withExclusiveInput(InputLeaseOptions(ownerLabel = "transaction")) {
                            driver.withBlockingInput("focusWindow") {}
                        }
                    }
                    true
                }

            assertTrue(completed.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("exclusiveInput"), coordinator.operations)
            assertEquals(1, coordinator.closedLeases)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent top-level operations on one driver are serialized`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val guard =
            InputLeaseGuard(
                policy = InputLeasePolicy.Required,
                capabilities = InputCapabilities(realOsInput = true, sharedSystemClipboard = true),
                coordinator = coordinator,
            )
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            guard.withOperation("first", CoordinatedResource.REAL_INPUT) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            guard.withOperation("second", CoordinatedResource.REAL_INPUT) {
                secondEntered.complete(Unit)
            }
        }
        yield()

        assertFalse(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf("first", "second"), coordinator.operations)
    }

    @Test
    fun `ambient whole-test lease prevents factory driver self-contention`() {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator)
        val wholeTestLease = recordingLease()

        AmbientInputLease.withLease(wholeTestLease) { runBlocking { driver.click(10, 20) } }

        assertEquals(2, wholeTestLease.checkpoints)
        assertTrue(coordinator.operations.isEmpty())
    }

    @Test
    fun `ambient whole-test lease fences type burst at operation checkpoint`() {
        val coordinator = RecordingInputLeaseCoordinator()
        val robot = LeaseTestRobotAdapter()
        val driver =
            RobotDriver(
                robot = robot,
                clipboard = LeaseTestClipboardAdapter(),
                inputLeasePolicy = InputLeasePolicy.Required,
                inputLeaseCoordinator = coordinator,
                inputCapabilities =
                    InputCapabilities(realOsInput = true, sharedSystemClipboard = true),
            )
        val wholeTestLease = recordingLease(failCheckpointAt = 4)

        val failure =
            assertFailsWith<InputCoordinatorException> {
                AmbientInputLease.withLease(wholeTestLease) {
                    runBlocking { driver.typeText("abc") }
                }
            }

        assertEquals("FENCED", failure.errorCode)
        assertEquals(listOf("press:65", "release:65"), robot.events)
        assertTrue(coordinator.operations.isEmpty())
    }

    @Test
    fun `automator exclusive scope exposes coordinated input verbs and capabilities`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator)
        val automator = ComposeAutomator.inProcess(driver, discoverWindows = false)

        automator.withExclusiveInput(InputLeaseOptions(ownerLabel = "form")) {
            typeText("a")
            pressKey(java.awt.event.KeyEvent.VK_ENTER)
        }

        assertTrue(automator.inputCapabilities.realOsInput)
        assertEquals(listOf("exclusiveInput"), coordinator.operations)
    }

    @Test
    fun `off policy never contacts coordinator`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator, policy = InputLeasePolicy.Off)

        driver.click(1, 2)

        assertTrue(coordinator.operations.isEmpty())
    }

    @Test
    fun `closing a driver releases its coordinator session`() {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator)

        driver.close()

        assertEquals(1, coordinator.closeCount)
    }

    @Test
    fun `auto degrades only when the coordinator provider is unavailable`() = runTest {
        val coordinator =
            RecordingInputLeaseCoordinator(
                acquireFailure =
                    InputCoordinatorException(
                        "COORDINATOR_PROVIDER_MISSING",
                        "runtime artifact unavailable",
                    )
            )
        val autoDriver = realDriver(coordinator, policy = InputLeasePolicy.Auto)
        val requiredDriver = realDriver(coordinator, policy = InputLeasePolicy.Required)

        autoDriver.click(1, 2)
        val requiredFailure =
            assertFailsWith<InputCoordinatorException> { requiredDriver.click(1, 2) }

        assertEquals("COORDINATOR_PROVIDER_MISSING", requiredFailure.errorCode)
    }

    @Test
    fun `auto provider fallback remains reentrant for nested composite operations`() = runTest {
        val coordinator =
            RecordingInputLeaseCoordinator(
                acquireFailure =
                    InputCoordinatorException(
                        "COORDINATOR_PROVIDER_MISSING",
                        "runtime artifact unavailable",
                    )
            )
        val guard =
            InputLeaseGuard(
                policy = InputLeasePolicy.Auto,
                capabilities = InputCapabilities(realOsInput = true, sharedSystemClipboard = true),
                coordinator = coordinator,
            )

        withTimeout(1_000) {
            guard.withOperation("outer", CoordinatedResource.REAL_INPUT) {
                guard.withOperation("inner", CoordinatedResource.REAL_INPUT) {}
            }
        }

        assertEquals(listOf("outer"), coordinator.operations)
    }

    @Test
    fun `auto keeps non-provider coordinator failures loud`() = runTest {
        val coordinator =
            RecordingInputLeaseCoordinator(
                acquireFailure = InputCoordinatorException("FENCED", "lease was revoked")
            )
        val driver = realDriver(coordinator, policy = InputLeasePolicy.Auto)

        val failure = assertFailsWith<InputCoordinatorException> { driver.click(1, 2) }

        assertEquals("FENCED", failure.errorCode)
    }

    @Test
    fun `auto on EDT degrades when no coordinator session can be established`() {
        val coordinator =
            ProductionInputLeaseCoordinator(
                connectClient = {
                    throw InputCoordinatorException(
                        "COORDINATOR_PROVIDER_MISSING",
                        "runtime artifact unavailable",
                    )
                }
            )
        val driver = realDriver(coordinator, policy = InputLeasePolicy.Auto)
        var failure: Throwable? = null

        SwingUtilities.invokeAndWait {
            failure = runCatching { runBlocking { driver.click(1, 2) } }.exceptionOrNull()
        }

        coordinator.close()
        assertEquals(null, failure)
    }

    @Test
    fun `production coordinator retries and classifies connection IO failures`() = runBlocking {
        var attempts = 0
        val coordinator =
            ProductionInputLeaseCoordinator(
                connectClient = {
                    attempts += 1
                    throw IOException("coordinator did not become ready")
                }
            )

        val failure =
            assertFailsWith<InputCoordinatorException> {
                coordinator.acquire(InputLeaseOptions(), "click", immediate = false)
            }

        assertEquals(2, attempts)
        assertEquals("COORDINATOR_IO", failure.errorCode)
    }

    @Test
    fun `production coordinator reconnects after server epoch changes`() = runBlocking {
        val directory = Files.createTempDirectory("spc-r-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val resource = DesktopResourceKey("test/reconnect")
        var server =
            LocalCoordinatorServer(endpoint, idleTimeout = JavaDuration.ofMinutes(1)).also {
                it.start()
            }
        val coordinator =
            ProductionInputLeaseCoordinator(
                connectClient = { label ->
                    LocalInputCoordinatorClient.connect(endpoint, resource, label)
                }
            )
        try {
            coordinator.acquire(InputLeaseOptions(), "before restart", immediate = false).close()
            server.close()
            Files.deleteIfExists(endpoint.socketPath)
            server =
                LocalCoordinatorServer(endpoint, idleTimeout = JavaDuration.ofMinutes(1)).also {
                    it.start()
                }

            coordinator.acquire(InputLeaseOptions(), "after restart", immediate = false).close()
        } finally {
            coordinator.close()
            server.close()
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory.resolve("coordinator.lock"))
            Files.deleteIfExists(directory.resolve("recovery.properties.tmp"))
            Files.deleteIfExists(directory.resolve("recovery.properties"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `production coordinator reopens an idle session when owner label changes`() = runBlocking {
        val directory = Files.createTempDirectory("spc-label-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val resource = DesktopResourceKey("test/owner-label")
        val server =
            LocalCoordinatorServer(endpoint, idleTimeout = JavaDuration.ofMinutes(1)).also {
                it.start()
            }
        val coordinator =
            ProductionInputLeaseCoordinator(
                connectClient = { label ->
                    LocalInputCoordinatorClient.connect(endpoint, resource, label)
                }
            )
        try {
            coordinator.acquire(InputLeaseOptions(), "unlabeled", immediate = false).close()
            val labeled =
                coordinator.acquire(
                    InputLeaseOptions(ownerLabel = "submits checkout"),
                    "labeled",
                    immediate = false,
                )

            val status = LocalInputCoordinatorControl(endpoint).status(resource)
            assertTrue(status is CoordinatorControlResult.Active)
            assertEquals("submits checkout", status.status.holder?.owner?.label)

            labeled.close()
        } finally {
            coordinator.close()
            server.close()
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory.resolve("coordinator.lock"))
            Files.deleteIfExists(directory.resolve("recovery.properties.tmp"))
            Files.deleteIfExists(directory.resolve("recovery.properties"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `cancelling queued production acquisition removes waiter and permits successor`() =
        runBlocking {
            val directory = Files.createTempDirectory("spc-core-cancel-")
            val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
            val resource = DesktopResourceKey("test/coroutine-cancellation")
            val server =
                LocalCoordinatorServer(
                        endpoint,
                        heartbeatTimeout = JavaDuration.ofSeconds(5),
                        idleTimeout = JavaDuration.ofMinutes(1),
                    )
                    .also { it.start() }
            val holderClient = LocalInputCoordinatorClient.connect(endpoint, resource, "holder")
            val holder = holderClient.acquire(JavaDuration.ofSeconds(2), "holder")
            val acquisitionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val coordinator =
                ProductionInputLeaseCoordinator(
                    connectClient = { label ->
                        LocalInputCoordinatorClient.connect(endpoint, resource, label)
                    }
                )
            try {
                val cancelled =
                    async(acquisitionDispatcher) {
                        assertFailsWith<kotlinx.coroutines.TimeoutCancellationException> {
                            withTimeout(100) {
                                coordinator.acquire(
                                    InputLeaseOptions(
                                        acquireTimeout = kotlin.time.Duration.parse("10s")
                                    ),
                                    "cancelled",
                                    immediate = false,
                                )
                            }
                        }
                    }
                awaitWaiterCount(endpoint, resource, 1)
                cancelled.await()
                awaitWaiterCount(endpoint, resource, 0)
                holder.close()

                coordinator.acquire(InputLeaseOptions(), "successor", immediate = false).close()
            } finally {
                coordinator.close()
                acquisitionDispatcher.close()
                holder.close()
                holderClient.close()
                server.close()
                Files.deleteIfExists(endpoint.socketPath)
                Files.deleteIfExists(directory.resolve("coordinator.lock"))
                Files.deleteIfExists(directory.resolve("recovery.properties.tmp"))
                Files.deleteIfExists(directory.resolve("recovery.properties"))
                Files.deleteIfExists(directory)
            }
        }

    @Test
    fun `cancellation after production acquisition closes the discarded lease`() = runTest {
        val directory = Files.createTempDirectory("spc-h-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val resource = DesktopResourceKey("test/cancelled-handoff")
        val server =
            LocalCoordinatorServer(endpoint, idleTimeout = JavaDuration.ofMinutes(1)).also {
                it.start()
            }
        val acquisitionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val coordinator =
            ProductionInputLeaseCoordinator(
                ioDispatcher = acquisitionDispatcher,
                connectClient = { label ->
                    LocalInputCoordinatorClient.connect(endpoint, resource, label)
                },
            )
        try {
            val discarded =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.acquire(InputLeaseOptions(), "discarded", immediate = false)
                }
            awaitHolder(endpoint, resource)

            discarded.cancel()
            discarded.join()

            LocalInputCoordinatorClient.connect(endpoint, resource, "successor").use { successor ->
                successor.acquire(JavaDuration.ofSeconds(2), "successor").close()
            }
        } finally {
            coordinator.close()
            acquisitionDispatcher.close()
            server.close()
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory.resolve("coordinator.lock"))
            Files.deleteIfExists(directory.resolve("recovery.properties.tmp"))
            Files.deleteIfExists(directory.resolve("recovery.properties"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `driver-bound lease takes precedence even when operation acquisition is off`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver = realDriver(coordinator, policy = InputLeasePolicy.Off)
        val boundLease = recordingLease()

        driver.bindInputLease(boundLease).use { driver.click(1, 2) }

        assertEquals(1, boundLease.checkpoints)
        assertTrue(coordinator.operations.isEmpty())
    }

    @Test
    fun `binding a second live test lease fails closed`() {
        val driver = realDriver(RecordingInputLeaseCoordinator(), policy = InputLeasePolicy.Off)
        val firstBinding = driver.bindInputLease(recordingLease())

        try {
            assertFailsWith<IllegalStateException> { driver.bindInputLease(recordingLease()) }
        } finally {
            firstBinding.close()
        }
    }

    @Test
    fun `synthetic pointer bypasses coordinator while shared clipboard participates`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver =
            RobotDriver(
                robot = LeaseTestRobotAdapter(),
                clipboard = LeaseTestClipboardAdapter(),
                inputLeasePolicy = InputLeasePolicy.Auto,
                inputLeaseCoordinator = coordinator,
                inputCapabilities =
                    InputCapabilities(realOsInput = false, sharedSystemClipboard = true),
            )

        driver.click(1, 2)
        driver.pasteText("hello")

        assertEquals(listOf("pasteText"), coordinator.operations)
    }

    @Test
    fun `synthetic focus participates when coordination is required`() {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver =
            RobotDriver(
                robot = LeaseTestRobotAdapter(),
                clipboard = LeaseTestClipboardAdapter(),
                inputLeasePolicy = InputLeasePolicy.Required,
                inputLeaseCoordinator = coordinator,
                inputCapabilities =
                    InputCapabilities(realOsInput = false, sharedSystemClipboard = false),
            )

        driver.withBlockingInput("focusWindow") {}

        assertEquals(listOf("focusWindow"), coordinator.operations)
    }

    @Test
    fun `synthetic explicit scope coordinates focus for the whole scope`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver =
            RobotDriver(
                robot = LeaseTestRobotAdapter(),
                clipboard = LeaseTestClipboardAdapter(),
                inputLeasePolicy = InputLeasePolicy.Required,
                inputLeaseCoordinator = coordinator,
                inputCapabilities =
                    InputCapabilities(realOsInput = false, sharedSystemClipboard = false),
            )

        driver.withExclusiveInput(InputLeaseOptions(ownerLabel = "synthetic scope")) {
            driver.withBlockingInput("focusWindow") {}
        }

        assertEquals(listOf("exclusiveInput"), coordinator.operations)
        assertEquals(1, coordinator.closedLeases)
    }

    @Test
    fun `headless failure happens without coordinator acquisition`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator()
        val driver =
            RobotDriver(
                robot = ThrowingLeaseTestRobotAdapter,
                clipboard = ThrowingLeaseTestClipboardAdapter,
                tccGuard = MacOsTccGuard.noop(),
                screenCapture = ThrowingLeaseTestRobotAdapter,
                inputLeasePolicy = InputLeasePolicy.Required,
                inputLeaseCoordinator = coordinator,
                inputCapabilities =
                    InputCapabilities(realOsInput = false, sharedSystemClipboard = false),
            )

        assertFailsWith<UnsupportedOperationException> { driver.click(1, 2) }
        assertTrue(coordinator.operations.isEmpty())
    }

    @Test
    fun `contended EDT operation requests immediate acquisition instead of waiting`() {
        val coordinator = RecordingInputLeaseCoordinator(failImmediate = true)
        val driver = realDriver(coordinator)
        var failure: Throwable? = null

        SwingUtilities.invokeAndWait {
            failure = runCatching { runBlocking { driver.click(1, 2) } }.exceptionOrNull()
        }

        assertTrue(failure is ContendedEdtInputLeaseException)
        assertEquals(listOf(true), coordinator.immediateRequests)
    }

    @Test
    fun `fenced type burst stops at next character checkpoint`() = runTest {
        val coordinator = RecordingInputLeaseCoordinator(failCheckpointAt = 3)
        val robot = LeaseTestRobotAdapter()
        val driver =
            RobotDriver(
                robot = robot,
                clipboard = LeaseTestClipboardAdapter(),
                inputLeasePolicy = InputLeasePolicy.Required,
                inputLeaseCoordinator = coordinator,
                inputCapabilities =
                    InputCapabilities(realOsInput = true, sharedSystemClipboard = true),
            )

        val failure = assertFailsWith<InputCoordinatorException> { driver.typeText("abc") }

        assertEquals("FENCED", failure.errorCode)
        assertEquals(listOf("press:65", "release:65"), robot.events)
    }

    private fun realDriver(
        coordinator: InputLeaseCoordinator,
        policy: InputLeasePolicy = InputLeasePolicy.Required,
    ): RobotDriver =
        RobotDriver(
            robot = LeaseTestRobotAdapter(),
            clipboard = LeaseTestClipboardAdapter(),
            inputLeasePolicy = policy,
            inputLeaseCoordinator = coordinator,
            inputCapabilities = InputCapabilities(realOsInput = true, sharedSystemClipboard = true),
        )

    private fun awaitWaiterCount(
        endpoint: CoordinatorEndpoint,
        resource: DesktopResourceKey,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val status = LocalInputCoordinatorControl(endpoint).status(resource)
            val active = status as? CoordinatorControlResult.Active
            if (active?.status?.waiters?.size == expected) return
            Thread.onSpinWait()
        }
        assertTrue(false, "Timed out waiting for $expected queued lease request")
    }

    private fun awaitHolder(endpoint: CoordinatorEndpoint, resource: DesktopResourceKey) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val status = LocalInputCoordinatorControl(endpoint).status(resource)
            val active = status as? CoordinatorControlResult.Active
            if (active?.status?.holder != null) return
            Thread.onSpinWait()
        }
        assertTrue(false, "Timed out waiting for a granted lease")
    }
}

private fun recordingLease(failCheckpointAt: Int? = null): RecordingCoordinatedInputLease =
    RecordingCoordinatedInputLease(failCheckpointAt)

private class RecordingCoordinatedInputLease(private val failCheckpointAt: Int?) :
    CoordinatedInputLease {
    var checkpoints: Int = 0

    override val token =
        LeaseToken(
            coordinatorEpoch = "epoch",
            leaseId = "bound-lease",
            resourceKey = DesktopResourceKey("test/desktop"),
            fence = 1,
        )

    override fun isValid(): Boolean = true

    override fun checkpoint() {
        checkpoints += 1
        if (checkpoints == failCheckpointAt) {
            throw InputCoordinatorException("FENCED", "revoked")
        }
    }

    override fun close(): Unit = Unit
}

private open class LeaseTestRobotAdapter : RobotAdapter {
    val events = mutableListOf<String>()
    override val autoDelayMs: Int = 0
    override val requiresOffEdt: Boolean = false

    override fun mouseMove(x: Int, y: Int): Unit = Unit

    override fun mousePress(buttons: Int): Unit = Unit

    override fun mouseRelease(buttons: Int): Unit = Unit

    override fun keyPress(keyCode: Int) {
        events += "press:$keyCode"
    }

    override fun keyRelease(keyCode: Int) {
        events += "release:$keyCode"
    }

    override fun mouseWheel(wheelClicks: Int): Unit = Unit

    override fun waitForIdle(): Unit = Unit

    override fun createScreenCapture(region: Rectangle): BufferedImage =
        BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
}

private class LeaseTestClipboardAdapter : ClipboardAdapter {
    private var contents: Transferable? = null

    override val supportsRead: Boolean = false

    override fun getContents(): Transferable? = contents

    override fun setContents(contents: Transferable) {
        this.contents = contents
    }
}

private object ThrowingLeaseTestRobotAdapter : LeaseTestRobotAdapter() {
    override fun mouseMove(x: Int, y: Int): Unit = throw UnsupportedOperationException("headless")
}

private object ThrowingLeaseTestClipboardAdapter : ClipboardAdapter {
    override fun getContents(): Transferable? = throw UnsupportedOperationException("headless")

    override fun setContents(contents: Transferable): Unit =
        throw UnsupportedOperationException("headless")
}

private class RecordingInputLeaseCoordinator(
    private val failImmediate: Boolean = false,
    private val failCheckpointAt: Int? = null,
    private val acquireFailure: InputCoordinatorException? = null,
) : InputLeaseCoordinator {
    val operations = mutableListOf<String>()
    val immediateRequests = mutableListOf<Boolean>()
    var closedLeases: Int = 0
    var closeCount: Int = 0

    override suspend fun acquire(
        options: InputLeaseOptions,
        currentOperation: String,
        immediate: Boolean,
    ): CoordinatedInputLease {
        operations += currentOperation
        immediateRequests += immediate
        acquireFailure?.let { throw it }
        if (immediate && failImmediate) {
            throw ContendedEdtInputLeaseException("held by another test")
        }
        return object : CoordinatedInputLease {
            private var checkpoints: Int = 0

            override val token =
                LeaseToken(
                    coordinatorEpoch = "epoch",
                    leaseId = "lease-${operations.size}",
                    resourceKey = DesktopResourceKey("test/desktop"),
                    fence = 1,
                )

            override fun isValid(): Boolean = true

            override fun checkpoint() {
                checkpoints += 1
                if (checkpoints == failCheckpointAt) {
                    throw InputCoordinatorException("FENCED", "revoked")
                }
            }

            override fun close() {
                closedLeases += 1
            }
        }
    }

    override fun close() {
        closeCount += 1
    }
}

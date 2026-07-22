@file:OptIn(org.jetbrains.compose.reload.DelicateHotReloadApi::class)

package dev.sebastiano.spectre.cli.hotreload

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.reload.core.getBlocking
import org.jetbrains.compose.reload.core.getOrThrow
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.asChannel
import org.jetbrains.compose.reload.orchestration.connectOrchestrationClient
import org.jetbrains.compose.reload.orchestration.sendBlocking
import org.jetbrains.compose.reload.orchestration.startOrchestrationServer
import org.junit.jupiter.api.Timeout

/**
 * Exercises the real `hot-reload-orchestration` client against an in-process orchestration server
 * (#211). This is the integration seam when a full `hotRun` fixture is unavailable (#208 still
 * open): the settle chain uses the same HR APIs as production.
 */
class HotReloadSessionIntegrationTest {
    @Test
    @Timeout(30)
    fun `waitForReloadSettled returns Settled only after full request result UIRendered Ping Ack chain`() {
        val server = startOrchestrationServer()
        val port = server.port.getBlocking(15.seconds).getOrThrow()
        val appScope = applicationScope()
        val appJob = startAckingApplicationClient(port, appScope)
        val session = HotReloadSession.forPort(port)
        try {
            awaitConnected(session)

            val outcomeRef = AtomicReference<ReloadSettleOutcome?>(null)
            val done = CountDownLatch(1)
            val waiter =
                Thread(
                    {
                        outcomeRef.set(session.waitForReloadSettled(timeoutMs = 15_000))
                        done.countDown()
                    },
                    "wait-for-reload-settled",
                )
            waiter.start()
            // Give the waiter a moment to subscribe before broadcasting the chain.
            Thread.sleep(200)

            val request = OrchestrationMessage.ReloadClassesRequest()
            server sendBlocking request
            // Result alone must not complete the wait.
            Thread.sleep(100)
            assertTrue(
                waiter.isAlive,
                "settle must wait for UIRendered, not only ReloadClassesResult",
            )

            server sendBlocking
                OrchestrationMessage.ReloadClassesResult(
                    reloadRequestId = request.messageId,
                    isSuccess = true,
                )
            Thread.sleep(100)
            assertTrue(waiter.isAlive, "settle must wait for UIRendered after successful result")

            server sendBlocking
                OrchestrationMessage.UIRendered(
                    windowId = null,
                    reloadRequestId = request.messageId,
                    iteration = 1,
                )

            assertTrue(done.await(10, TimeUnit.SECONDS), "settle did not complete in time")
            assertEquals(ReloadSettleOutcome.Settled, outcomeRef.get())
        } finally {
            session.close()
            appJob.cancel()
            appScope.cancel()
            server.close()
        }
    }

    @Test
    @Timeout(30)
    fun `waitForReloadSettled surfaces reload failure from ReloadClassesResult`() {
        val server = startOrchestrationServer()
        val port = server.port.getBlocking(15.seconds).getOrThrow()
        val appScope = applicationScope()
        val appJob = startAckingApplicationClient(port, appScope)
        val session = HotReloadSession.forPort(port)
        try {
            awaitConnected(session)

            val outcomeRef = AtomicReference<ReloadSettleOutcome?>(null)
            val done = CountDownLatch(1)
            Thread(
                    {
                        outcomeRef.set(session.waitForReloadSettled(timeoutMs = 15_000))
                        done.countDown()
                    },
                    "wait-for-reload-failed",
                )
                .start()
            Thread.sleep(200)

            val request = OrchestrationMessage.ReloadClassesRequest()
            server sendBlocking request
            server sendBlocking
                OrchestrationMessage.ReloadClassesResult(
                    reloadRequestId = request.messageId,
                    isSuccess = false,
                    errorMessage = "synthetic redefine failure",
                )

            assertTrue(done.await(10, TimeUnit.SECONDS))
            val failed = assertIs<ReloadSettleOutcome.ReloadFailed>(outcomeRef.get())
            assertEquals("synthetic redefine failure", failed.errorMessage)
        } finally {
            session.close()
            appJob.cancel()
            appScope.cancel()
            server.close()
        }
    }

    @Test
    fun `waitForReloadSettled is unavailable when no orchestration is connected`() {
        val session =
            HotReloadSession.forTesting(
                portProvider = { null },
                connect = { null },
                startReconnect = false,
            )
        try {
            assertEquals(
                ReloadSettleOutcome.Unavailable,
                session.waitForReloadSettled(timeoutMs = 100),
            )
        } finally {
            session.close()
        }
    }

    private fun applicationScope(
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): CoroutineScope = CoroutineScope(dispatcher)

    private fun awaitConnected(session: HotReloadSession, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!session.isConnected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(session.isConnected, "Tooling client did not connect to orchestration server")
    }

    /**
     * Application-role client that replies to [OrchestrationMessage.Ping] with matching
     * [OrchestrationMessage.Ack], mirroring what a real HR app does for the Ping/Ack drain.
     */
    private fun startAckingApplicationClient(port: Int, scope: CoroutineScope): Job = scope.launch {
        val result = connectOrchestrationClient(OrchestrationClientRole.Application, port)
        val client = result.getOrThrow()
        val channel = client.asChannel()
        try {
            while (true) {
                val message = channel.receiveCatching().getOrNull() ?: break
                if (message is OrchestrationMessage.Ping) {
                    client.send(OrchestrationMessage.Ack(message.messageId))
                }
            }
        } finally {
            channel.cancel()
            client.close()
        }
    }
}

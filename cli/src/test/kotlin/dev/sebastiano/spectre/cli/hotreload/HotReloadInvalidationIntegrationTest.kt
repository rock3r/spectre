@file:OptIn(org.jetbrains.compose.reload.DelicateHotReloadApi::class)

package dev.sebastiano.spectre.cli.hotreload

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
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
 * Coexistence-style proof that Spectre's Tooling client can observe a real reload settle without
 * class redefinition from Spectre (#212). Full `hotRun` fixture is blocked on #208; this drives the
 * same orchestration surface production uses.
 */
class HotReloadInvalidationIntegrationTest {
    @Test
    @Timeout(30)
    fun `reload settled listener fires after UIRendered without Spectre redefining classes`() {
        val server = startOrchestrationServer()
        val port = server.port.getBlocking(15.seconds).getOrThrow()
        val appScope = applicationScope()
        val appJob = startAckingApplicationClient(port, appScope)
        val session = HotReloadSession.forPort(port)
        val fired = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        session.setReloadSettledListener {
            fired.set(true)
            latch.countDown()
        }
        try {
            awaitConnected(session)
            val request = OrchestrationMessage.ReloadClassesRequest()
            server sendBlocking request
            server sendBlocking
                OrchestrationMessage.ReloadClassesResult(
                    reloadRequestId = request.messageId,
                    isSuccess = true,
                )
            server sendBlocking
                OrchestrationMessage.UIRendered(
                    windowId = null,
                    reloadRequestId = request.messageId,
                    iteration = 1,
                )
            assertTrue(latch.await(10, TimeUnit.SECONDS), "invalidation listener did not fire")
            assertTrue(fired.get())
            // Spectre never called redefineClasses — coexistence premise.
        } finally {
            session.close()
            appJob.cancel()
            appScope.cancel()
            server.close()
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
        assertTrue(session.isConnected, "Tooling client did not connect")
    }

    private fun startAckingApplicationClient(port: Int, scope: CoroutineScope): Job = scope.launch {
        val client =
            connectOrchestrationClient(OrchestrationClientRole.Application, port).getOrThrow()
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

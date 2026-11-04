@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #574: an attach to an IntelliJ IDE that has not loaded Compose yet serves requests as a target
 * with no Compose windows, and switches to a real automator once Compose loads.
 */
class DeferredAutomatorHandlerTest {
    private val composeWindows = AgentResponse.Windows(emptyList())

    @Test
    fun `answers as a target with no Compose UI until Compose loads`() {
        val handler = DeferredAutomatorHandler(connect = { null })

        assertEquals(AgentResponse.Pong, handler.handle(AgentRequest.Ping))
        assertEquals(AgentResponse.Windows(emptyList()), handler.handle(AgentRequest.Windows))
        assertEquals(AgentResponse.Nodes(emptyList()), handler.handle(AgentRequest.AllNodes))
        assertEquals(
            AgentResponse.Nodes(emptyList()),
            handler.handle(AgentRequest.FindByTestTag("tag")),
        )
        assertEquals(
            AgentResponse.WindowIdentities(emptyList()),
            handler.handle(AgentRequest.WindowIdentity()),
        )
        assertEquals(AgentResponse.Ok, handler.handle(AgentRequest.WaitUntilGone(tag = "tag")))
    }

    @Test
    fun `refuses node and input operations until Compose loads`() {
        val handler = DeferredAutomatorHandler(connect = { null })

        assertErrorCategory(
            AgentErrorCategory.NodeNotFound,
            handler.handle(AgentRequest.Click("node")),
        )
        assertErrorCategory(
            AgentErrorCategory.NodeNotFound,
            handler.handle(AgentRequest.Screenshot()),
        )
        assertErrorCategory(
            AgentErrorCategory.InputRejected,
            handler.handle(AgentRequest.TypeText("hi")),
        )
    }

    @Test
    fun `delegates to the automator once Compose loads and connects only once`() {
        var composeLoaded = false
        var connects = 0
        val handler =
            DeferredAutomatorHandler(
                connect = {
                    if (composeLoaded) {
                        connects++
                        connected(handler = { composeWindows })
                    } else {
                        null
                    }
                }
            )
        assertEquals(AgentResponse.Windows(emptyList()), handler.handle(AgentRequest.Windows))

        composeLoaded = true
        assertEquals(composeWindows, handler.handle(AgentRequest.Windows))
        handler.handle(AgentRequest.AllNodes)

        assertEquals(1, connects)
    }

    @Test
    fun `waitForNode waits for Compose to load and passes on the time left`() {
        val clock = FakeClock()
        val delegated = mutableListOf<AgentRequest>()
        val handler =
            DeferredAutomatorHandler(
                connect = {
                    if (clock.now >= COMPOSE_LOADS_AT_MS) {
                        connected(
                            handler = { request ->
                                delegated += request
                                AgentResponse.Nodes(emptyList())
                            }
                        )
                    } else {
                        null
                    }
                },
                clock = clock::now,
                sleep = clock::advance,
            )

        handler.handle(
            AgentRequest.WaitForNode(tag = "tag", timeoutMs = 5_000, pollIntervalMs = 100)
        )

        val forwarded = assertIs<AgentRequest.WaitForNode>(delegated.single())
        assertEquals("tag", forwarded.tag)
        assertEquals(5_000 - COMPOSE_LOADS_AT_MS, forwarded.timeoutMs)
        assertEquals(100, forwarded.pollIntervalMs)
    }

    @Test
    fun `waitForNode times out when Compose never loads`() {
        val clock = FakeClock()
        val handler =
            DeferredAutomatorHandler(connect = { null }, clock = clock::now, sleep = clock::advance)

        val response = handler.handle(AgentRequest.WaitForNode(tag = "tag", timeoutMs = 1_000))

        assertErrorCategory(AgentErrorCategory.Timeout, response)
        assertTrue(clock.now >= 1_000, "the wait should use its whole timeout")
    }

    @Test
    fun `close releases the connected automator's input and stops connecting`() {
        var closed = 0
        var connects = 0
        val handler =
            DeferredAutomatorHandler(
                connect = {
                    connects++
                    connected(handler = { composeWindows }, targetInputResource = { closed++ })
                }
            )
        handler.handle(AgentRequest.Windows)

        handler.close()
        handler.close()

        assertEquals(1, closed)
        assertEquals(AgentResponse.Windows(emptyList()), handler.handle(AgentRequest.Windows))
        assertEquals(1, connects)
    }

    private fun connected(
        handler: dev.sebastiano.spectre.agent.transport.AgentRequestHandler,
        targetInputResource: AutoCloseable? = null,
    ) =
        ConnectedAutomator(
            automator = Any(),
            handler = handler,
            targetInputResource = targetInputResource,
        )

    private fun assertErrorCategory(expected: AgentErrorCategory, response: AgentResponse) {
        val error = assertIs<AgentResponse.Error>(response)
        assertEquals(expected.wireName, error.category)
    }

    private class FakeClock {
        var now: Long = 0
            private set

        fun advance(ms: Long) {
            now += ms
        }
    }

    private companion object {
        const val COMPOSE_LOADS_AT_MS: Long = 1_200
    }
}

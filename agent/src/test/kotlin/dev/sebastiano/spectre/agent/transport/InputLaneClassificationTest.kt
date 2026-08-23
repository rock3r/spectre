package dev.sebastiano.spectre.agent.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputLaneClassificationTest {
    @Test
    fun `only desktop input and focus requests use the bounded input lane`() {
        assertTrue(AgentRequest.Click("node").requiresInputLane)
        assertTrue(AgentRequest.DoubleClick("node").requiresInputLane)
        assertTrue(AgentRequest.LongClick("node").requiresInputLane)
        assertTrue(
            AgentRequest.Swipe("node", endX = 10, endY = 20, durationMs = 100).requiresInputLane
        )
        assertTrue(AgentRequest.ScrollWheel("node", 1).requiresInputLane)
        assertTrue(AgentRequest.PressKey(10).requiresInputLane)
        assertTrue(AgentRequest.FocusWindow("node").requiresInputLane)
        assertTrue(AgentRequest.TypeText("secret").requiresInputLane)

        assertFalse(AgentRequest.Windows.requiresInputLane)
        assertFalse(AgentRequest.AllNodes.requiresInputLane)
        assertFalse(AgentRequest.Screenshot().requiresInputLane)
        assertFalse(AgentRequest.WaitForIdle().requiresInputLane)
        assertFalse(AgentRequest.Cancel(1).requiresInputLane)
        assertFalse(AgentRequest.Detach.requiresInputLane)
    }
}

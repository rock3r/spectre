@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import kotlin.coroutines.Continuation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The OS keyboard focus gate on `typeText` / `pressKey` only applies to real OS input (#556).
 *
 * Synthetic input (the `spectre attach` default) dispatches key events in-process, so it never
 * reaches another app and does not need the target JVM to own OS focus. A synthetic `click` does
 * not activate the window either, so keeping the gate made `type` / `press-key` fail after `click`
 * whenever another app was in front, and always fail for `apple.awt.UIElement` apps.
 */
class ReflectiveAutomatorHandlerSyntheticFocusTest {

    @Test
    fun `synthetic typeText does not require OS keyboard focus`() {
        val fake = InputModeAutomator(realOsInput = false, focusedNode = true)
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { false })

        assertEquals(AgentResponse.Ok, handler.handle(AgentRequest.TypeText("hello")))
        assertEquals(listOf("hello"), fake.typedTexts)
    }

    @Test
    fun `synthetic typeText still requires a focused node`() {
        val fake = InputModeAutomator(realOsInput = false, focusedNode = false)
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { false })

        val response = handler.handle(AgentRequest.TypeText("hello"))

        check(response is AgentResponse.Error) { "expected an Error response, got $response" }
        assertEquals(AgentErrorCategory.InputRejected.wireName, response.category)
        assertTrue(response.message.contains("no focused", ignoreCase = true), response.message)
        assertEquals(emptyList(), fake.typedTexts)
    }

    @Test
    fun `real OS typeText still requires OS keyboard focus`() {
        val fake = InputModeAutomator(realOsInput = true, focusedNode = true)
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { false })

        val response = handler.handle(AgentRequest.TypeText("hello"))

        check(response is AgentResponse.Error) { "expected an Error response, got $response" }
        assertTrue(response.message.contains("OS keyboard focus"), response.message)
        assertEquals(emptyList(), fake.typedTexts)
    }

    @Test
    fun `synthetic pressKey does not require OS keyboard focus`() {
        val fake = InputModeAutomator(realOsInput = false, focusedNode = false)
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { false })

        assertEquals(AgentResponse.Ok, handler.handle(AgentRequest.PressKey(keyCode = 10)))
        assertEquals(listOf(10 to 0), fake.pressedKeys)
    }

    @Test
    fun `synthetic pressKey refuses when the target JVM has no window to dispatch to`() {
        val fake = InputModeAutomator(realOsInput = false, focusedNode = false, windowCount = 0)
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { false })

        val response = handler.handle(AgentRequest.PressKey(keyCode = 10))

        check(response is AgentResponse.Error) { "expected an Error response, got $response" }
        assertEquals(AgentErrorCategory.InputRejected.wireName, response.category)
        assertEquals(emptyList(), fake.pressedKeys)
    }

    @Test
    fun `real OS pressKey still requires OS keyboard focus`() {
        val fake = InputModeAutomator(realOsInput = true, focusedNode = false)
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { false })

        val response = handler.handle(AgentRequest.PressKey(keyCode = 10))

        check(response is AgentResponse.Error) { "expected an Error response, got $response" }
        assertEquals(AgentErrorCategory.InputRejected.wireName, response.category)
        assertEquals(emptyList(), fake.pressedKeys)
    }
}

/** Mirrors `InputCapabilities.realOsInput`; the handler reads it reflectively. */
private class FakeInputCapabilities(private val realOsInputValue: Boolean) {
    @Suppress("unused") fun getRealOsInput(): Boolean = realOsInputValue
}

private class FocusableFakeNode(private val keyValue: String, private val focused: Boolean) {
    @Suppress("unused") fun getKey(): String = keyValue

    @Suppress("unused") fun isFocused(): Boolean = focused
}

/** Exposes the method set the handler needs, plus `getInputCapabilities()`. */
private class InputModeAutomator(
    realOsInput: Boolean,
    focusedNode: Boolean,
    windowCount: Int = 1,
) {
    val typedTexts = mutableListOf<String>()
    val pressedKeys = mutableListOf<Pair<Int, Int>>()

    private val capabilities = FakeInputCapabilities(realOsInput)
    private val nodes = listOf(FocusableFakeNode("field", focusedNode))
    private val windows = List(windowCount) { Any() }

    @Suppress("unused") fun getInputCapabilities(): Any = capabilities

    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = windows

    @Suppress("unused") fun allNodes(): List<Any> = nodes

    @Suppress("unused", "UNUSED_PARAMETER") fun findByTestTag(tag: String): List<Any> = emptyList()

    @Suppress("unused", "UNUSED_PARAMETER")
    fun pressKey(keyCode: Int, modifiers: Int, continuation: Continuation<Any?>): Any? {
        pressedKeys += keyCode to modifiers
        return Unit
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun typeText(text: String, continuation: Continuation<Any?>): Any? {
        typedTexts += text
        return Unit
    }
}

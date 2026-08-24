@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Coordinated input verbs available while [ComposeAutomator.withExclusiveInput] owns a lease. */
@ExperimentalSpectreInputCoordinationApi
public class ExclusiveInputScope internal constructor(private val automator: ComposeAutomator) {
    public suspend fun click(node: AutomatorNode): Unit = automator.click(node)

    public suspend fun doubleClick(node: AutomatorNode): Unit = automator.doubleClick(node)

    public suspend fun longClick(node: AutomatorNode, holdFor: Duration = 500.milliseconds): Unit =
        automator.longClick(node, holdFor)

    public suspend fun moveTo(node: AutomatorNode): Unit = automator.moveTo(node)

    public suspend fun moveTo(x: Int, y: Int): Unit = automator.moveTo(x, y)

    public suspend fun moveBy(deltaX: Int, deltaY: Int): Unit = automator.moveBy(deltaX, deltaY)

    public suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        steps: Int = 12,
        duration: Duration = 200.milliseconds,
    ): Unit = automator.swipe(startX, startY, endX, endY, steps, duration)

    public suspend fun swipe(
        from: AutomatorNode,
        to: AutomatorNode,
        steps: Int = 12,
        duration: Duration = 200.milliseconds,
    ): Unit = automator.swipe(from, to, steps, duration)

    public suspend fun scrollWheel(node: AutomatorNode, wheelClicks: Int): Unit =
        automator.scrollWheel(node, wheelClicks)

    public suspend fun typeText(text: String): Unit = automator.typeText(text)

    public suspend fun pasteText(text: String): Unit = automator.pasteText(text)

    public suspend fun clearAndTypeText(node: AutomatorNode, text: String): Unit =
        automator.clearAndTypeText(node, text)

    public suspend fun pressKey(keyCode: Int, modifiers: Int = 0): Unit =
        automator.pressKey(keyCode, modifiers)

    public suspend fun pressEnter(): Unit = automator.pressEnter()

    public fun focusWindow(node: AutomatorNode) {
        automator.checkpointInputLease()
        automator.focusWindowUncoordinated(node)
    }
}

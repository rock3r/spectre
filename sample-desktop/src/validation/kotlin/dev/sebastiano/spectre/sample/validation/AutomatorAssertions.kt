package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Polls [block] until it returns a non-null value or [timeout] elapses, refreshing the automator's
 * tracked windows on each iteration. Throws [AssertionError] with [description] in the message on
 * timeout.
 *
 * Tests use this anywhere the UI mutates asynchronously — popups opening, secondary windows
 * appearing, scenario navigation completing.
 */
fun <T : Any> ComposeAutomator.eventually(
    description: String,
    timeout: Duration = 5.seconds,
    pollInterval: Duration = 50.milliseconds,
    block: ComposeAutomator.() -> T?,
): T {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    var lastError: Throwable? = null
    while (deadline.hasNotPassedNow()) {
        refreshWindows()
        try {
            block()?.let {
                return it
            }
        } catch (t: Throwable) {
            lastError = t
        }
        Thread.sleep(pollInterval.inWholeMilliseconds)
    }
    throw AssertionError("Timeout after $timeout waiting for: $description", lastError)
}

/** Wait for a node with the given testTag to appear and return it. */
fun ComposeAutomator.waitForTestTag(tag: String, timeout: Duration = 5.seconds): AutomatorNode =
    eventually(description = "node with testTag '$tag'", timeout = timeout) {
        findOneByTestTag(tag)
    }

/** Wait until no node with the given testTag is present. */
fun ComposeAutomator.waitUntilGone(tag: String, timeout: Duration = 5.seconds) {
    eventually(description = "no node with testTag '$tag'", timeout = timeout) {
        if (findOneByTestTag(tag) == null) Unit else null
    }
}

/**
 * Click the picker entry for a scenario by its `scenario.<name>` testTag, then wait until the
 * right-pane `scenario.title` node reflects that scenario's title. Comparing against the picker
 * entry's text (with the selected-state "▸ " prefix stripped) means we wait for the actual
 * recomposition rather than just the title node's existence — `scenario.title` is always present,
 * so checking only for non-null text would let follow-up assertions race the previous scenario's
 * UI.
 */
fun ComposeAutomator.navigateToScenario(scenarioTag: String) {
    val pickerEntry = waitForTestTag(scenarioTag)
    val expectedTitle = pickerEntry.text?.removePrefix("▸ ")?.trim()
    click(pickerEntry)
    eventually(description = "scenario '$scenarioTag' to become selected") {
        val titleNode = findOneByTestTag("scenario.title") ?: return@eventually null
        when {
            expectedTitle == null -> titleNode.takeIf { it.text != null }
            titleNode.text == expectedTitle -> titleNode
            else -> null
        }
    }
}

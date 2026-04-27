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
 * right-pane `scenario.title` node either gains a value (first navigation) or its text changes
 * (subsequent navigations). The title node is always present in the picker layout, so the prior
 * "wait for non-null title" check would return immediately and let follow-up assertions race the
 * previous scenario's UI.
 *
 * If the navigation happens to land on the same scenario already selected (so the title text
 * doesn't change), the click is still observable through the picker entry's selected-state "▸ "
 * prefix — so we accept either a title-change OR a picker entry that's now marked selected.
 */
fun ComposeAutomator.navigateToScenario(scenarioTag: String) {
    val pickerEntryBefore = waitForTestTag(scenarioTag)
    val previousTitle = findOneByTestTag("scenario.title")?.text
    val pickerWasSelected = pickerEntryBefore.text?.startsWith("▸") == true
    click(pickerEntryBefore)
    eventually(description = "scenario '$scenarioTag' to become selected") {
        val titleNode = findOneByTestTag("scenario.title") ?: return@eventually null
        val pickerNow = findOneByTestTag(scenarioTag) ?: return@eventually null
        val titleChanged = titleNode.text != null && titleNode.text != previousTitle
        val pickerNowSelected = pickerNow.text?.startsWith("▸") == true
        when {
            previousTitle == null && titleNode.text != null -> titleNode
            titleChanged -> titleNode
            // Re-selecting the current scenario: the picker entry was already marked, so the
            // click is a no-op as far as state is concerned. Accept this as settled.
            pickerWasSelected && pickerNowSelected -> titleNode
            else -> null
        }
    }
}

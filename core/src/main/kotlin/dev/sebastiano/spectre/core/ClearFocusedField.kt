package dev.sebastiano.spectre.core

import java.awt.event.KeyEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Selects the focused field and deletes its contents.
 *
 * Ctrl/Cmd+A is the documented select-all chord. Spectre deliberately does not follow it with a
 * Home / Shift+End line selection: that would collapse a successful multiline selection to the
 * current line and silently leave stale text behind. An adapter that cannot guarantee the chord
 * refuses before any key is dispatched, so Backspace can never run without a known selection.
 */
internal suspend fun RobotAdapter.clearFocusedField(selectAllModifier: Int) {
    check(supportsReliableSelectAll) {
        "clearAndTypeText cannot safely select-all on this input backend; " +
            "the focused field was left untouched"
    }
    pressChord(selectAllModifier, KeyEvent.VK_A)
    settleAfterClearChord()
    tapKey(KeyEvent.VK_BACK_SPACE)
    settleAfterClearChord()
}

private fun RobotAdapter.tapKey(keyCode: Int) {
    keyPress(keyCode)
    keyRelease(keyCode)
}

private fun RobotAdapter.pressChord(modifier: Int, keyCode: Int) {
    keyPress(modifier)
    try {
        tapKey(keyCode)
    } finally {
        keyRelease(modifier)
    }
}

private suspend fun RobotAdapter.settleAfterClearChord() {
    waitForIdle()
    if (deliversRealOsInput && autoDelayMs <= 0) {
        delay(CLEAR_CHORD_SETTLE_MS.milliseconds)
    }
}

private const val CLEAR_CHORD_SETTLE_MS: Long = 20L

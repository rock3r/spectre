package dev.sebastiano.spectre.core

import java.awt.event.KeyEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Selects the focused field and deletes its contents.
 *
 * Ctrl/Cmd+A is the documented select-all chord, but Wayland NotifyKeyboardKeysym often does not
 * apply Control as a sticky modifier to the following key — Linux robot smoke then appended (`hello
 * linuxreplaced`). Home then Shift+End selects the current line without relying on Control.
 */
internal suspend fun RobotAdapter.clearFocusedField(selectAllModifier: Int) {
    pressChord(selectAllModifier, KeyEvent.VK_A)
    settleAfterClearChord()
    tapKey(KeyEvent.VK_HOME)
    pressChord(KeyEvent.VK_SHIFT, KeyEvent.VK_END)
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

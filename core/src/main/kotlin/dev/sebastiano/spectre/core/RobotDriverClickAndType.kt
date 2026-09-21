@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import java.awt.event.KeyEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Clicks [screenX], [screenY], moves the caret to the end of the focused field, then types [text],
 * holding one desktop input lease for the whole sequence.
 *
 * Separate [RobotDriver.click] and [RobotDriver.typeText] calls each take and release a lease. On
 * Windows the gap is enough for another `Robot` JVM to activate a different window, and a later
 * click can drop the caret in the middle of text that already landed. One lease keeps the other
 * client out until the last character is released. End is pressed after the click so the caret is
 * at the end of a single-line field before typing appends.
 *
 * EDT callers require coordination to be off, already held, or immediately available; contention
 * fails without blocking. Throws [IllegalStateException] on macOS if Accessibility TCC permission
 * is denied.
 */
public suspend fun RobotDriver.clickAndTypeText(screenX: Int, screenY: Int, text: String) {
    inputCoordination.withOperation("clickAndTypeText", CoordinatedResource.REAL_INPUT) {
        click(screenX, screenY)
        delay(CLICK_AND_TYPE_FOCUS_SETTLE)
        pressKey(KeyEvent.VK_END)
        typeText(text)
    }
}

private val CLICK_AND_TYPE_FOCUS_SETTLE = 400.milliseconds

package dev.sebastiano.spectre.core

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyEvent

/** Flavorless clipboard value used to restore a clipboard that was genuinely empty. */
private object EmptyClipboardContents : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = false

    override fun getTransferData(flavor: DataFlavor): Nothing =
        throw UnsupportedFlavorException(flavor)
}

internal fun saveClipboardContents(clipboard: ClipboardAdapter): Transferable {
    val saved = runCatching { clipboard.getContents() ?: EmptyClipboardContents }
    val failure = saved.exceptionOrNull()
    if (failure is UnsupportedOperationException) throw failure
    if (failure != null) {
        throw IllegalStateException("pasteText could not save the clipboard", failure)
    }
    return saved.getOrThrow()
}

internal suspend fun withRestoredClipboard(
    clipboard: ClipboardAdapter,
    previousContents: Transferable,
    block: suspend () -> Unit,
) {
    val operation = runCatching { block() }
    val restoreFailure = runCatching { clipboard.setContents(previousContents) }.exceptionOrNull()
    val primaryFailure = operation.exceptionOrNull()
    if (primaryFailure != null) {
        if (restoreFailure != null) primaryFailure.addSuppressed(restoreFailure)
        throw primaryFailure
    }
    if (restoreFailure != null) {
        throw IllegalStateException("pasteText could not restore the clipboard", restoreFailure)
    }
}

internal fun prepareTextInput(robot: RobotAdapter, text: String): List<KeyStrokeSpec> {
    val capsLockOffStrokes = text.map { keyStrokeForChar(it) }
    val hasLetters = text.any { it in 'a'..'z' || it in 'A'..'Z' }
    if (!hasLetters || !robot.deliversRealOsInput) return capsLockOffStrokes

    val capsLockOn =
        robot.getLockingKeyState(KeyEvent.VK_CAPS_LOCK)
            ?: error("typeText cannot guarantee letter case because Caps Lock state is unknown")
    return if (capsLockOn) text.map { keyStrokeForChar(it, capsLockOn = true) }
    else capsLockOffStrokes
}

internal suspend fun typePreparedText(
    robot: RobotAdapter,
    strokes: List<KeyStrokeSpec>,
    checkpoint: suspend () -> Unit,
) {
    for (stroke in strokes) {
        checkpoint()
        for (modifier in stroke.modifiers) robot.keyPress(modifier)
        try {
            robot.keyPress(stroke.keyCode)
            robot.keyRelease(stroke.keyCode)
        } finally {
            for (modifier in stroke.modifiers.asReversed()) {
                runCatching { robot.keyRelease(modifier) }
            }
        }
    }
}

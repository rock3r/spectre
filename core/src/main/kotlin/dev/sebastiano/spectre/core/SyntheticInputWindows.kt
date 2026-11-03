package dev.sebastiano.spectre.core

import java.awt.Component
import java.awt.Dialog
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * The windows that may receive synthetic input, lowest priority first.
 *
 * [SyntheticRobotAdapter] walks this list in reverse for hit testing and the Compose key-target
 * lookup, so the last entry wins. AWT does not expose the real stacking order, so top-level windows
 * keep their creation order: a window shown later usually sits above older ones, so a secondary
 * window the app opens wins over the window that opened it, and an older background window loses to
 * a newer one in front of it. Each window's owned tree follows it, so popups win over their owner.
 * While a modal [Dialog] is showing, the windows it blocks are dropped and the dialog's own tree
 * goes last, as AWT does for real input.
 */
internal fun syntheticInputWindows(): List<Window> {
    val collected = LinkedHashSet<Window>()
    // Walk every top-level window (and its owned tree). The visibility check is applied per-window
    // by the callers rather than here: Swing's `SharedOwnerFrame` (parent of `JDialog(null, …)`)
    // is itself a top-level window that is NOT showing, but its owned dialogs may be — so filtering
    // hidden top-levels out here would drop those dialogs and synthetic input would silently miss
    // them, even though `WindowTracker` correctly surfaces them.
    for (window in Window.getWindows()) {
        if (window.owner == null) collectWindowTree(window, collected)
    }
    val modal = activeModalDialog() ?: return collected.toList()
    val modalTree = LinkedHashSet<Window>().also { collectWindowTree(modal, it) }
    return collected.filter { it !in modalTree && !modal.blocks(it) } + modalTree
}

/** The most recently created modal dialog that is showing, or `null` if none is. */
internal fun activeModalDialog(): Dialog? =
    Window.getWindows().filterIsInstance<Dialog>().lastOrNull { it.isModal && it.isShowing }

/**
 * Whether the showing modal dialog, if any, blocks input to the window that holds this component.
 */
internal fun Component.isBlockedByModalDialog(): Boolean {
    val modal = activeModalDialog() ?: return false
    val window = this as? Window ?: SwingUtilities.getWindowAncestor(this) ?: return false
    return modal.blocks(window)
}

/**
 * Whether this showing modal dialog blocks input to [window], following AWT's modality rules: a
 * dialog never blocks itself or the windows it owns, and a document-modal dialog blocks only the
 * windows of its own document (the same owner-less root). Application- and toolkit-modal dialogs
 * block everything else, except windows excluded from that modality through
 * [Window.getModalExclusionType], on the window itself or on one of its owners.
 */
private fun Dialog.blocks(window: Window): Boolean {
    if (window.isSameOrOwnedBy(this)) return false
    return when (modalityType) {
        Dialog.ModalityType.DOCUMENT_MODAL -> window.documentRoot() === documentRoot()
        Dialog.ModalityType.APPLICATION_MODAL ->
            !window.isModalExcluded(Dialog.ModalExclusionType.APPLICATION_EXCLUDE)
        Dialog.ModalityType.TOOLKIT_MODAL ->
            !window.isModalExcluded(Dialog.ModalExclusionType.TOOLKIT_EXCLUDE)
        else -> true
    }
}

/** Mirrors AWT: TOOLKIT_EXCLUDE also covers APPLICATION_EXCLUDE, and owners pass exclusion down. */
private fun Window.isModalExcluded(type: Dialog.ModalExclusionType): Boolean =
    generateSequence(this) { it.owner }.any { it.modalExclusionType >= type }

private fun Window.isSameOrOwnedBy(ancestor: Window): Boolean =
    generateSequence(this) { it.owner }.any { it === ancestor }

private fun Window.documentRoot(): Window = generateSequence(this) { it.owner }.last()

private fun collectWindowTree(window: Window, into: MutableCollection<Window>) {
    into += window
    for (child in window.ownedWindows) {
        collectWindowTree(child, into)
    }
}

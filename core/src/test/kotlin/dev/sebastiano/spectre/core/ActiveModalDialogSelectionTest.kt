package dev.sebastiano.spectre.core

import java.awt.Dialog
import java.awt.Window
import java.util.concurrent.FutureTask
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * How [selectActiveModalDialog] picks the interactive modal dialog when several are showing (#589).
 *
 * The dialogs are created but never shown: the selection only reads the owner chain, and the caller
 * passes the showing modals, AWT's blocked state, and the active window explicitly.
 */
class ActiveModalDialogSelectionTest {

    private val windows = mutableListOf<Window>()

    @AfterTest
    fun disposeAll() {
        SwingUtilities.invokeAndWait { windows.reversed().forEach(Window::dispose) }
        windows.clear()
    }

    @Test
    fun `prefers the dialog AWT does not block over creation order`() {
        assumeLiveAwtAvailable()
        val root = frame()
        val earlier = modal(root)
        val newer = modal(root)

        val selected =
            selectActiveModalDialog(
                showingModals = listOf(earlier, newer),
                isModalBlocked = { it === newer },
                activeWindow = null,
            )

        assertSame(earlier, selected)
    }

    @Test
    fun `without AWT's blocked state prefers the dialog that is the active window`() {
        assumeLiveAwtAvailable()
        val root = frame()
        val earlier = modal(root)
        val newer = modal(root)

        val selected =
            selectActiveModalDialog(
                showingModals = listOf(earlier, newer),
                isModalBlocked = null,
                activeWindow = earlier,
            )

        assertSame(earlier, selected)
    }

    @Test
    fun `without AWT's blocked state prefers the closest dialog that owns the active window`() {
        assumeLiveAwtAvailable()
        val root = frame()
        val earlier = modal(root)
        val newer = modal(root)
        val nested = modal(earlier)
        val earlierChild = onEdt { JDialog(earlier, "child").also { windows += it } }

        val forChild =
            selectActiveModalDialog(
                showingModals = listOf(earlier, newer),
                isModalBlocked = null,
                activeWindow = earlierChild,
            )
        val forNested =
            selectActiveModalDialog(
                showingModals = listOf(earlier, newer, nested),
                isModalBlocked = null,
                activeWindow = nested,
            )

        assertSame(earlier, forChild)
        assertSame(nested, forNested)
    }

    @Test
    fun `falls back to the newest dialog without other information`() {
        assumeLiveAwtAvailable()
        val root = frame()
        val earlier = modal(root)
        val newer = modal(root)

        val selected =
            selectActiveModalDialog(
                showingModals = listOf(earlier, newer),
                isModalBlocked = null,
                activeWindow = root,
            )

        assertSame(newer, selected)
    }

    private fun frame(): JFrame = onEdt { JFrame("root").also { windows += it } }

    private fun modal(owner: Window): JDialog = onEdt {
        JDialog(owner, "modal", Dialog.ModalityType.APPLICATION_MODAL).also { windows += it }
    }

    private fun <T> onEdt(block: () -> T): T {
        val task = FutureTask<T> { block() }
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}

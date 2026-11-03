package dev.sebastiano.spectre.core

import androidx.compose.ui.awt.ComposePanel
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/**
 * Window ordering, modal scoping, and modal-loop behaviour of the synthetic driver in Swing hosts
 * that mix several top-level windows and modal dialogs (#565).
 *
 * Every test shows real AWT windows, so each one starts with [assumeLiveAwtAvailable]. Modal
 * dialogs are always shown with `invokeLater` (never from the test thread, which would block), and
 * every driver call that could enter a modal loop runs on a helper thread joined with a timeout, so
 * a regression fails the test instead of hanging the JVM.
 */
class SyntheticModalAndWindowOrderTest {

    private val windows = CopyOnWriteArrayList<Window>()

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `pinned root window wins hit testing over a later top-level window at the same spot`() {
        assumeLiveAwtAvailable()
        val rootTarget = RecordingPanel()
        val otherTarget = RecordingPanel()
        try {
            val root = showFrame(rootTarget)
            // Created after the root, so it comes later in Window.getWindows(): it models a
            // background frame that happens to overlap the pinned root on screen.
            showFrame(otherTarget)
            val (cx, cy) = centerOnScreen(rootTarget)

            runBlocking { RobotDriver.synthetic(root).click(cx, cy) }
            drainEdt()

            assertTrue(
                rootTarget.pressed.isNotEmpty(),
                "expected the click to reach the pinned root window",
            )
            assertTrue(
                otherTarget.pressed.isEmpty(),
                "another top-level window must not intercept clicks meant for the pinned root",
            )
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `an active modal dialog blocks synthetic clicks on the window it blocks`() {
        assumeLiveAwtAvailable()
        val rootTarget = RecordingPanel()
        val dialogTarget = RecordingPanel()
        try {
            val root = showFrame(rootTarget)
            showModalDialog(owner = root, content = dialogTarget, x = DIALOG_X)
            val driver = RobotDriver.synthetic(root)

            val (rootX, rootY) = centerOnScreen(rootTarget)
            runOffTestThread { runBlocking { driver.click(rootX, rootY) } }
            val (dialogX, dialogY) = centerOnScreen(dialogTarget)
            runOffTestThread { runBlocking { driver.click(dialogX, dialogY) } }
            drainEdt()

            assertTrue(
                rootTarget.pressed.isEmpty(),
                "a window blocked by a modal dialog must not receive synthetic clicks",
            )
            assertTrue(dialogTarget.pressed.isNotEmpty(), "the modal dialog must stay clickable")
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `an active modal dialog receives keys when AWT has no focus owner`() {
        assumeLiveAwtAvailable()
        val recorder = GlobalKeyRecorder().also { it.install() }
        try {
            val root =
                showFrame(JPanel().apply { preferredSize = PANEL_SIZE }) {
                    focusableWindowState = false
                }
            val dialog =
                showModalDialog(
                    owner = root,
                    content = JPanel().apply { preferredSize = PANEL_SIZE },
                    x = DIALOG_X,
                    focusable = false,
                )

            runOffTestThread { runBlocking { RobotDriver.synthetic(root).pressKey(KeyEvent.VK_A) } }
            drainEdt()

            val pressed =
                recorder.events.firstOrNull {
                    it.id == KeyEvent.KEY_PRESSED && it.keyCode == KeyEvent.VK_A
                }
            assertNotNull(pressed, "expected a KEY_PRESSED for VK_A")
            val source = pressed.source as Component
            val sourceWindow = source as? Window ?: SwingUtilities.getWindowAncestor(source)
            assertEquals(
                dialog,
                sourceWindow,
                "keys must go to the active modal dialog, not the window it blocks; source=$source",
            )
        } finally {
            recorder.uninstall()
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `a click whose release opens a modal dialog returns and the dialog stays drivable`() {
        assumeLiveAwtAvailable()
        val dialogTarget = RecordingPanel()
        val dialog = AtomicReference<JDialog?>()
        val opener =
            JPanel().apply {
                preferredSize = PANEL_SIZE
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseReleased(e: MouseEvent) {
                            val modal = newModalDialog(owner = windowAncestor(), dialogTarget)
                            dialog.set(modal)
                            // Blocks inside a nested event loop until the dialog closes.
                            modal.isVisible = true
                        }
                    }
                )
            }
        try {
            val root = showFrame(opener)
            val driver = RobotDriver.synthetic(root)
            val (cx, cy) = centerOnScreen(opener)

            runOffTestThread { runBlocking { driver.click(cx, cy) } }
            waitUntil("modal dialog showing") { dialog.get()?.isShowing == true }

            val (dx, dy) = centerOnScreen(dialogTarget)
            runOffTestThread { runBlocking { driver.click(dx, dy) } }
            drainEdt()
            assertTrue(
                dialogTarget.pressed.isNotEmpty(),
                "the modal dialog opened by the click must receive the next synthetic click",
            )
        } finally {
            disposeAll()
            dialog.get()?.let { modal -> SwingUtilities.invokeLater { modal.dispose() } }
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `a key press that opens a modal dialog returns`() {
        assumeLiveAwtAvailable()
        val dialog = AtomicReference<JDialog?>()
        val opener =
            JPanel().apply {
                preferredSize = PANEL_SIZE
                addKeyListener(
                    object : KeyAdapter() {
                        override fun keyPressed(e: KeyEvent) {
                            if (e.keyCode != KeyEvent.VK_ENTER || dialog.get() != null) return
                            val modal =
                                newModalDialog(
                                    owner = windowAncestor(),
                                    JPanel().apply { preferredSize = PANEL_SIZE },
                                )
                            dialog.set(modal)
                            modal.isVisible = true
                        }
                    }
                )
            }
        try {
            val root = showFrame(opener) { focusableWindowState = false }
            val driver = RobotDriver.synthetic(root)
            val (cx, cy) = centerOnScreen(opener)

            runOffTestThread {
                runBlocking {
                    driver.moveTo(cx, cy)
                    driver.pressKey(KeyEvent.VK_ENTER)
                }
            }
            waitUntil("modal dialog showing") { dialog.get()?.isShowing == true }
        } finally {
            disposeAll()
            dialog.get()?.let { modal -> SwingUtilities.invokeLater { modal.dispose() } }
        }
    }

    @Test
    fun `Compose host detection walks the superclass chain`() {
        assertTrue(ComposePanel::class.java.isComposeHostClass(), "ComposePanel itself")
        // Compose renders through anonymous subclasses of the Skiko layers: SkiaLayer for
        // ComposeWindow and SkiaSwingLayer for ComposePanel. Exact class-name matching misses
        // both, so the lookup has to walk superclasses.
        for (name in COMPOSE_SKIA_LAYER_SUBCLASSES) {
            val subclass = Class.forName(name, false, javaClass.classLoader)
            assertTrue(subclass.isComposeHostClass(), "$name must count as a Compose host")
        }
        assertFalse(JPanel::class.java.isComposeHostClass(), "plain Swing panels are not hosts")
    }

    // --- Fixtures ---------------------------------------------------------------------------

    private fun showFrame(content: JPanel, configure: JFrame.() -> Unit = {}): JFrame {
        val frame = onEdt {
            JFrame("SyntheticModalAndWindowOrderTest").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                isUndecorated = true
                configure()
                contentPane.layout = BorderLayout()
                contentPane.add(content, BorderLayout.CENTER)
                size = PANEL_SIZE
                setLocation(FRAME_X, FRAME_Y)
                isVisible = true
            }
        }
        windows += frame
        waitUntil("frame showing") { content.isShowing }
        return frame
    }

    private fun showModalDialog(
        owner: Window,
        content: JPanel,
        x: Int,
        focusable: Boolean = true,
    ): JDialog {
        val modal = onEdt {
            newModalDialog(owner, content).apply {
                focusableWindowState = focusable
                setLocation(x, FRAME_Y)
            }
        }
        windows += modal
        // setVisible(true) on a modal dialog blocks in a nested loop, so it must be posted.
        SwingUtilities.invokeLater { modal.isVisible = true }
        waitUntil("modal dialog showing") { content.isShowing }
        return modal
    }

    private fun newModalDialog(owner: Window?, content: JPanel): JDialog =
        JDialog(owner, "modal", Dialog.ModalityType.APPLICATION_MODAL).apply {
            isUndecorated = true
            contentPane.layout = BorderLayout()
            contentPane.add(content, BorderLayout.CENTER)
            size = PANEL_SIZE
            setLocation(DIALOG_X, FRAME_Y)
        }

    private fun JPanel.windowAncestor(): Window? = SwingUtilities.getWindowAncestor(this)

    private fun centerOnScreen(target: JPanel): Pair<Int, Int> {
        var center = 0 to 0
        SwingUtilities.invokeAndWait {
            val origin = target.locationOnScreen
            center = (origin.x + target.width / 2) to (origin.y + target.height / 2)
        }
        return center
    }

    private fun <T> onEdt(block: () -> T): T {
        val task = FutureTask<T> { block() }
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }

    /** Runs [block] on a helper thread and fails (instead of hanging) if it does not return. */
    private fun runOffTestThread(block: () -> Unit) {
        var failure: Throwable? = null
        val worker =
            thread(isDaemon = true, name = "synthetic-driver-call") {
                try {
                    block()
                } catch (t: Throwable) {
                    failure = t
                }
            }
        worker.join(TimeUnit.SECONDS.toMillis(CALL_TIMEOUT_SECONDS))
        check(!worker.isAlive) {
            "synthetic driver call did not return within ${CALL_TIMEOUT_SECONDS}s " +
                "(blocked behind a modal event loop?)"
        }
        failure?.let { throw it }
    }

    private fun waitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALL_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && !condition()) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        check(condition()) { "timed out waiting for $description" }
    }

    private fun drainEdt() {
        repeat(2) { SwingUtilities.invokeAndWait {} }
    }

    private fun disposeAll() {
        // invokeLater so a wedged EDT can't block cleanup; a modal loop still drains these.
        for (window in windows.reversed()) {
            SwingUtilities.invokeLater {
                window.isVisible = false
                window.dispose()
            }
        }
        windows.clear()
    }

    private class RecordingPanel : JPanel() {
        val pressed: MutableList<MouseEvent> = CopyOnWriteArrayList()

        init {
            preferredSize = PANEL_SIZE
            addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        pressed += e
                    }
                }
            )
        }
    }

    private class GlobalKeyRecorder {
        val events: MutableList<KeyEvent> = CopyOnWriteArrayList()
        private val listener = AWTEventListener { event ->
            if (event is KeyEvent) events.add(event)
        }

        fun install() {
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.KEY_EVENT_MASK)
        }

        fun uninstall() {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS: Long = 30L
        const val CALL_TIMEOUT_SECONDS: Long = 5L
        const val POLL_INTERVAL_MS: Long = 25L
        const val FRAME_X: Int = 120
        const val FRAME_Y: Int = 120
        const val DIALOG_X: Int = 400
        val PANEL_SIZE = Dimension(200, 200)
        val COMPOSE_SKIA_LAYER_SUBCLASSES =
            listOf(
                "androidx.compose.ui.scene.skia.SurfaceSkiaLayerComponent\$hierarchyRoot\$1",
                "androidx.compose.ui.scene.skia.SwingSkiaLayerComponent\$hierarchyRoot\$1",
            )
    }
}

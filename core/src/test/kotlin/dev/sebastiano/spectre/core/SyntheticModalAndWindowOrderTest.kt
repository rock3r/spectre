package dev.sebastiano.spectre.core

import androidx.compose.ui.awt.ComposePanel
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.lang.reflect.InvocationTargetException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    fun `pinned root window wins hit testing over an older top-level window at the same spot`() {
        assumeLiveAwtAvailable()
        val rootTarget = RecordingPanel()
        val otherTarget = RecordingPanel()
        try {
            // Created before the root: a background frame that the newer root covers on screen.
            showFrame(otherTarget)
            val root = showFrame(rootTarget)
            val (cx, cy) = centerOnScreen(rootTarget)

            runBlocking { RobotDriver.synthetic(root).click(cx, cy) }
            drainEdt()

            assertTrue(
                rootTarget.pressed.isNotEmpty(),
                "expected the click to reach the pinned root window",
            )
            assertTrue(
                otherTarget.pressed.isEmpty(),
                "an older window behind the pinned root must not intercept its clicks",
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
            assertSyntheticRejection {
                runOffTestThread { runBlocking { driver.click(rootX, rootY) } }
            }
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
    fun `an overlapping active modal dialog wins hit testing over its pinned owner`() {
        assumeLiveAwtAvailable()
        val ownerTarget = RecordingPanel()
        val dialogTarget = RecordingPanel()
        try {
            val owner = showFrame(ownerTarget)
            val dialog =
                showModalDialog(owner = owner, content = dialogTarget, x = FRAME_X + OVERLAP_OFFSET)
            val driver = RobotDriver.synthetic(owner)

            val dialogX = onEdt { owner.x + owner.width - OVERLAP_OFFSET / 2 }
            val dialogY = onEdt { owner.y + owner.height / 2 }
            runOffTestThread { runBlocking { driver.click(dialogX, dialogY) } }
            val blockedOwnerX = onEdt { owner.x + OVERLAP_OFFSET / 2 }
            val blockedOwnerY = onEdt { owner.y + owner.height / 2 }
            assertSyntheticRejection {
                runOffTestThread { runBlocking { driver.click(blockedOwnerX, blockedOwnerY) } }
            }
            drainEdt()

            assertTrue(dialog.isShowing, "the modal fixture must remain live for both gestures")
            assertTrue(dialogTarget.pressed.isNotEmpty(), "the overlap must hit the modal dialog")
            assertTrue(
                ownerTarget.pressed.isEmpty(),
                "the blocked pinned owner must receive neither overlapping nor outside clicks",
            )
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `an active modal dialog rejects keys when AWT has no focus owner`() {
        assumeLiveAwtAvailable()
        val recorder = GlobalKeyRecorder().also { it.install() }
        try {
            val root =
                showFrame(JPanel().apply { preferredSize = PANEL_SIZE }) {
                    focusableWindowState = false
                }
            showModalDialog(
                owner = root,
                content = JPanel().apply { preferredSize = PANEL_SIZE },
                x = DIALOG_X,
                focusable = false,
            )

            assertSyntheticRejection {
                runOffTestThread {
                    runBlocking { RobotDriver.synthetic(root).pressKey(KeyEvent.VK_A) }
                }
            }
            drainEdt()

            assertTrue(
                recorder.events.none { it.keyCode == KeyEvent.VK_A },
                "a key without a focused key-listening recipient must not be delivered",
            )
        } finally {
            recorder.uninstall()
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `a window excluded from application modality stays clickable under a modal dialog`() {
        assumeLiveAwtAvailable()
        assumeTrue(
            Toolkit.getDefaultToolkit()
                .isModalExclusionTypeSupported(Dialog.ModalExclusionType.APPLICATION_EXCLUDE),
            "this toolkit does not support modal exclusion",
        )
        val excludedTarget = RecordingPanel()
        try {
            val root = showFrame(RecordingPanel())
            showFrame(excludedTarget, x = EXCLUDED_X) {
                modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE
            }
            showModalDialog(owner = root, content = RecordingPanel(), x = DIALOG_X)
            val (x, y) = centerOnScreen(excludedTarget)

            runOffTestThread { runBlocking { RobotDriver.synthetic(root).click(x, y) } }
            drainEdt()

            assertTrue(
                excludedTarget.pressed.isNotEmpty(),
                "a window excluded from application modality must stay clickable",
            )
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `an earlier-created modal dialog shown over a newer one receives synthetic clicks`() {
        assumeLiveAwtAvailable()
        val earlierTarget = RecordingPanel()
        val newerTarget = RecordingPanel()
        try {
            val root = showFrame(RecordingPanel())
            // A is created first but not shown yet, like a reused or pre-created dialog.
            val earlier = onEdt {
                newModalDialog(root, earlierTarget).apply { setLocation(DIALOG_X, FRAME_Y) }
            }
            windows += earlier
            showModalDialog(owner = root, content = newerTarget, x = EXCLUDED_X)
            // Runs inside B's modal loop, like a handler in B that opens A.
            SwingUtilities.invokeLater { earlier.isVisible = true }
            waitUntil("earlier modal dialog showing") { earlierTarget.isShowing }
            val driver = RobotDriver.synthetic(root)

            val (earlierX, earlierY) = centerOnScreen(earlierTarget)
            runOffTestThread { runBlocking { driver.click(earlierX, earlierY) } }
            val (newerX, newerY) = centerOnScreen(newerTarget)
            assertSyntheticRejection {
                runOffTestThread { runBlocking { driver.click(newerX, newerY) } }
            }
            drainEdt()

            assertTrue(
                earlierTarget.pressed.isNotEmpty(),
                "the modal dialog shown last must receive synthetic clicks, even if created first",
            )
            assertTrue(
                newerTarget.pressed.isEmpty(),
                "the modal dialog blocked by the one shown over it must not receive clicks",
            )
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `a tool window excluded from application modality stays clickable under a document-modal dialog`() {
        assumeLiveAwtAvailable()
        assumeTrue(
            Toolkit.getDefaultToolkit()
                .isModalExclusionTypeSupported(Dialog.ModalExclusionType.APPLICATION_EXCLUDE),
            "this toolkit does not support modal exclusion",
        )
        val toolTarget = RecordingPanel()
        try {
            val root = showFrame(RecordingPanel())
            showToolWindow(owner = root, content = toolTarget, x = EXCLUDED_X) {
                modalExclusionType = Dialog.ModalExclusionType.APPLICATION_EXCLUDE
            }
            showModalDialog(
                owner = root,
                content = RecordingPanel(),
                x = DIALOG_X,
                modality = Dialog.ModalityType.DOCUMENT_MODAL,
            )
            val (x, y) = centerOnScreen(toolTarget)

            runOffTestThread { runBlocking { RobotDriver.synthetic(root).click(x, y) } }
            drainEdt()

            assertTrue(
                toolTarget.pressed.isNotEmpty(),
                "a tool window excluded from application modality must stay clickable under a " +
                    "document-modal dialog of the same document",
            )
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `a click whose release hides its target completes`() {
        assumeLiveAwtAvailable()
        val target =
            JPanel().apply {
                preferredSize = PANEL_SIZE
                addMouseListener(
                    object : MouseAdapter() {
                        override fun mouseReleased(e: MouseEvent) {
                            // Like a button whose action closes its own window.
                            SwingUtilities.getWindowAncestor(e.component)?.isVisible = false
                        }
                    }
                )
            }
        try {
            val root = showFrame(target)
            val (x, y) = centerOnScreen(target)

            // Fails if MOUSE_CLICKED is dispatched to the now-hidden component.
            runOffTestThread { runBlocking { RobotDriver.synthetic(root).click(x, y) } }
        } finally {
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
            val modal = checkNotNull(dialog.get())
            windows += modal
            stabilizeWindowLocation(modal, DIALOG_X, FRAME_Y)

            val (dx, dy) = centerOnScreen(dialogTarget)
            runOffTestThread { runBlocking { driver.click(dx, dy) } }
            drainEdt()
            assertTrue(
                dialogTarget.pressed.isNotEmpty(),
                "the modal dialog opened by the click must receive the next synthetic click",
            )
        } finally {
            disposeAll()
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
                isFocusable = true
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
            val root = showFrame(opener)
            focusOnEdt(root, opener)
            val driver = RobotDriver.synthetic(root)

            runOffTestThread { runBlocking { driver.pressKey(KeyEvent.VK_ENTER) } }
            waitUntil("modal dialog showing") { dialog.get()?.isShowing == true }
            windows += checkNotNull(dialog.get())
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `Enter whose pressed handler hides its recipient drops its typed tail`() {
        assumeLiveAwtAvailable()
        val originalEvents = CopyOnWriteArrayList<Int>()
        val replacementEvents = CopyOnWriteArrayList<Int>()
        val originalFrame = AtomicReference<JFrame>()
        val replacement =
            JPanel().apply {
                preferredSize = PANEL_SIZE
                isFocusable = true
                addKeyListener(recordKeyEventIds(replacementEvents))
            }
        try {
            val replacementFrame = showFrame(replacement, x = DIALOG_X)
            val original =
                JPanel().apply {
                    preferredSize = PANEL_SIZE
                    isFocusable = true
                    addKeyListener(
                        object : KeyAdapter() {
                            override fun keyPressed(event: KeyEvent) {
                                originalEvents += event.id
                                if (event.keyCode != KeyEvent.VK_ENTER) return
                                originalFrame.get().isVisible = false
                                replacementFrame.toFront()
                                replacementFrame.requestFocus()
                                replacement.requestFocus()
                            }

                            override fun keyTyped(event: KeyEvent) {
                                originalEvents += event.id
                            }

                            override fun keyReleased(event: KeyEvent) {
                                originalEvents += event.id
                            }
                        }
                    )
                }
            val root = showFrame(original)
            originalFrame.set(root)
            focusOnEdt(root, original)

            runOffTestThread {
                runBlocking { RobotDriver.synthetic(root).pressKey(KeyEvent.VK_ENTER) }
            }
            drainEdt()

            assertEquals(listOf(KeyEvent.KEY_PRESSED), originalEvents.toList())
            assertTrue(
                replacementEvents.isEmpty(),
                "the typed/released tail must not leak into the newly focused window",
            )
        } finally {
            disposeAll()
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `Escape whose pressed handler disposes its recipient completes without a release`() {
        assumeLiveAwtAvailable()
        val events = CopyOnWriteArrayList<Int>()
        val frame = AtomicReference<JFrame>()
        val target =
            JPanel().apply {
                preferredSize = PANEL_SIZE
                isFocusable = true
                addKeyListener(
                    object : KeyAdapter() {
                        override fun keyPressed(event: KeyEvent) {
                            events += event.id
                            if (event.keyCode == KeyEvent.VK_ESCAPE) frame.get().dispose()
                        }

                        override fun keyReleased(event: KeyEvent) {
                            events += event.id
                        }
                    }
                )
            }
        try {
            val root = showFrame(target)
            frame.set(root)
            focusOnEdt(root, target)

            runOffTestThread {
                runBlocking { RobotDriver.synthetic(root).pressKey(KeyEvent.VK_ESCAPE) }
            }
            drainEdt()

            assertEquals(listOf(KeyEvent.KEY_PRESSED), events.toList())
        } finally {
            disposeAll()
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

    private fun showFrame(
        content: JPanel,
        x: Int = FRAME_X,
        configure: JFrame.() -> Unit = {},
    ): JFrame {
        val frame = onEdt {
            JFrame("SyntheticModalAndWindowOrderTest").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                isUndecorated = true
                configure()
                contentPane.layout = BorderLayout()
                contentPane.add(content, BorderLayout.CENTER)
                size = PANEL_SIZE
                setLocation(x, FRAME_Y)
                isVisible = true
            }
        }
        windows += frame
        waitUntil("frame showing") { content.isShowing }
        stabilizeWindowLocation(frame, x, FRAME_Y)
        return frame
    }

    private fun showToolWindow(
        owner: Window,
        content: JPanel,
        x: Int,
        configure: JDialog.() -> Unit = {},
    ): JDialog {
        val tool = onEdt {
            JDialog(owner, "tool", Dialog.ModalityType.MODELESS).apply {
                isUndecorated = true
                configure()
                contentPane.layout = BorderLayout()
                contentPane.add(content, BorderLayout.CENTER)
                size = PANEL_SIZE
                setLocation(x, FRAME_Y)
                isVisible = true
            }
        }
        windows += tool
        waitUntil("tool window showing") { content.isShowing }
        stabilizeWindowLocation(tool, x, FRAME_Y)
        return tool
    }

    private fun showModalDialog(
        owner: Window,
        content: JPanel,
        x: Int,
        focusable: Boolean = true,
        modality: Dialog.ModalityType = Dialog.ModalityType.APPLICATION_MODAL,
    ): JDialog {
        val modal = onEdt {
            newModalDialog(owner, content, modality).apply {
                focusableWindowState = focusable
                setLocation(x, FRAME_Y)
            }
        }
        windows += modal
        // setVisible(true) on a modal dialog blocks in a nested loop, so it must be posted.
        SwingUtilities.invokeLater { modal.isVisible = true }
        waitUntil("modal dialog showing") { content.isShowing }
        stabilizeWindowLocation(modal, x, FRAME_Y)
        return modal
    }

    private fun newModalDialog(
        owner: Window?,
        content: JPanel,
        modality: Dialog.ModalityType = Dialog.ModalityType.APPLICATION_MODAL,
    ): JDialog =
        JDialog(owner, "modal", modality).apply {
            isUndecorated = true
            contentPane.layout = BorderLayout()
            contentPane.add(content, BorderLayout.CENTER)
            size = PANEL_SIZE
            setLocation(DIALOG_X, FRAME_Y)
        }

    private fun JPanel.windowAncestor(): Window? = SwingUtilities.getWindowAncestor(this)

    private fun recordKeyEventIds(events: MutableList<Int>): KeyAdapter =
        object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                events += event.id
            }

            override fun keyTyped(event: KeyEvent) {
                events += event.id
            }

            override fun keyReleased(event: KeyEvent) {
                events += event.id
            }
        }

    private fun assertSyntheticRejection(block: () -> Unit) {
        val failure = assertFailsWith<InvocationTargetException>(block = block)
        assertTrue(
            failure.targetException is IllegalStateException,
            "expected synthetic input rejection, got ${failure.targetException}",
        )
    }

    private fun focusOnEdt(frame: JFrame, target: JPanel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALL_TIMEOUT_SECONDS)
        var consecutiveFocusedReads = 0
        while (System.nanoTime() < deadline && consecutiveFocusedReads < 2) {
            SwingUtilities.invokeAndWait {
                frame.toFront()
                frame.requestFocus()
                target.requestFocus()
            }
            drainEdt()
            if (onEdt { frame.focusOwner === target }) consecutiveFocusedReads++
            else consecutiveFocusedReads = 0
            if (consecutiveFocusedReads < 2) Thread.sleep(POLL_INTERVAL_MS)
        }
        check(onEdt { frame.focusOwner === target }) {
            "the headed environment did not grant the enabled, showing key listener AWT focus; " +
                "frame focused=${frame.isFocused}, active=${frame.isActive}, " +
                "focusOwner=${frame.focusOwner}, target showing=${target.isShowing}, " +
                "enabled=${target.isEnabled}, focusable=${target.isFocusable}"
        }
    }

    private fun centerOnScreen(target: JPanel): Pair<Int, Int> {
        var center = 0 to 0
        SwingUtilities.invokeAndWait {
            val origin = target.locationOnScreen
            center = (origin.x + target.width / 2) to (origin.y + target.height / 2)
        }
        return center
    }

    private fun stabilizeWindowLocation(window: Window, x: Int, y: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALL_TIMEOUT_SECONDS)
        var consecutiveMatches = 0
        while (System.nanoTime() < deadline && consecutiveMatches < 2) {
            SwingUtilities.invokeAndWait { window.setLocation(x, y) }
            drainEdt()
            if (onEdt { window.locationOnScreen.x == x && window.locationOnScreen.y == y }) {
                consecutiveMatches++
            } else {
                consecutiveMatches = 0
            }
            if (consecutiveMatches < 2) Thread.sleep(POLL_INTERVAL_MS)
        }
        check(consecutiveMatches == 2) {
            "window manager did not settle the fixture at ($x,$y); " +
                "actual=${onEdt { window.locationOnScreen }} bounds=${onEdt { window.bounds }}"
        }
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
        drainEdt()
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
        const val OVERLAP_OFFSET: Int = 100
        const val DIALOG_X: Int = 400
        const val EXCLUDED_X: Int = 680
        val PANEL_SIZE = Dimension(200, 200)
        val COMPOSE_SKIA_LAYER_SUBCLASSES =
            listOf(
                "androidx.compose.ui.scene.skia.SurfaceSkiaLayerComponent\$hierarchyRoot\$1",
                "androidx.compose.ui.scene.skia.SwingSkiaLayerComponent\$hierarchyRoot\$1",
            )
    }
}

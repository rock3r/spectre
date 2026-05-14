package dev.sebastiano.spectre.core

import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout

/**
 * Parity coverage for the synthetic AWT driver (R4). Each test asserts an **observable** AWT
 * contract through a real `JFrame` + listener — never peeking at adapter internals. The synthetic
 * driver runs alongside the real `java.awt.Robot` path in production, so when this suite drifts
 * from what AWT actually emits, real-world behaviour drifts with it.
 *
 * Test scaffolding rules (per R4 plan guardrails):
 * - `assumeLiveAwtAvailable()` on every test — synthetic dispatch needs a real AWT display, and
 *   macOS AppKit initialisation is opt-in for default unit-test stability.
 * - Frames are constructed and disposed on the EDT.
 * - Event-queue drains use `SwingUtilities.invokeAndWait { }` rather than time-based sleeps.
 * - `@Timeout` backstops every test so a regression doesn't pin the JVM via a non-daemon EDT.
 */
class SyntheticInputParityTest {

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `mouseWheel emits one MouseWheelEvent per call with scrollAmount 1 and signed wheelRotation`() {
        assumeLiveAwtAvailable()
        val target = WheelTargetPanel()
        val frame = showFrameOnEdt(target)
        try {
            val (cx, cy) = frame.targetCenterOnScreen(target)
            val driver = RobotDriver.synthetic(frame.frame)
            runBlocking { driver.scrollWheel(cx, cy, 3) }
            runBlocking { driver.scrollWheel(cx, cy, -2) }
            drainEdt()

            assertEquals(2, target.events.size, "expected one MouseWheelEvent per scrollWheel call")
            val first = target.events[0]
            assertEquals(
                1,
                first.scrollAmount,
                "scrollAmount must always be 1 per the adapter contract",
            )
            assertEquals(
                3,
                first.wheelRotation,
                "wheelRotation must be the signed wheelClicks input",
            )
            assertEquals(
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                first.scrollType,
                "scrollType must be WHEEL_UNIT_SCROLL",
            )
            assertEquals(3, first.unitsToScroll, "unitsToScroll = scrollAmount * wheelRotation = 3")
            val second = target.events[1]
            assertEquals(
                -2,
                second.wheelRotation,
                "negative wheelClicks must produce negative rotation",
            )
            assertEquals(-2, second.unitsToScroll, "unitsToScroll mirrors the signed input")
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `double-click sequence reports clickCount=2 on the second pair`() {
        assumeLiveAwtAvailable()
        val target = MouseTargetPanel()
        val frame = showFrameOnEdt(target)
        try {
            val (cx, cy) = frame.targetCenterOnScreen(target)
            val driver = RobotDriver.synthetic(frame.frame)
            runBlocking { driver.click(cx, cy) }
            runBlocking { driver.click(cx, cy) }
            drainEdt()

            // We expect two press/release/click triples, with the second triple reporting
            // clickCount=2. Filter out MOUSE_MOVED noise so the sequence assertion is precise.
            val significant =
                target.events.filter {
                    it.id == MouseEvent.MOUSE_PRESSED ||
                        it.id == MouseEvent.MOUSE_RELEASED ||
                        it.id == MouseEvent.MOUSE_CLICKED
                }
            val counts = significant.map { it.id to it.clickCount }
            assertEquals(
                listOf(
                    MouseEvent.MOUSE_PRESSED to 1,
                    MouseEvent.MOUSE_RELEASED to 1,
                    MouseEvent.MOUSE_CLICKED to 1,
                    MouseEvent.MOUSE_PRESSED to 2,
                    MouseEvent.MOUSE_RELEASED to 2,
                    MouseEvent.MOUSE_CLICKED to 2,
                ),
                counts,
                "expected click-count to advance to 2 on the second click pair, got $counts",
            )
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `swipe between distinct points emits no MOUSE_CLICKED`() {
        assumeLiveAwtAvailable()
        val target = MouseTargetPanel()
        val frame = showFrameOnEdt(target)
        try {
            val origin = frame.targetTopLeftOnScreen(target)
            val driver = RobotDriver.synthetic(frame.frame)
            val startX = origin.x + 10
            val startY = origin.y + 10
            val endX = origin.x + target.width - 10
            val endY = origin.y + target.height - 10
            runBlocking { driver.swipe(startX, startY, endX, endY) }
            drainEdt()

            val clicks = target.events.filter { it.id == MouseEvent.MOUSE_CLICKED }
            assertTrue(clicks.isEmpty(), "expected no MOUSE_CLICKED during a swipe, got $clicks")
            // Sanity: press + at least one drag + release happened.
            val pressed = target.events.count { it.id == MouseEvent.MOUSE_PRESSED }
            val released = target.events.count { it.id == MouseEvent.MOUSE_RELEASED }
            val dragged = target.events.count { it.id == MouseEvent.MOUSE_DRAGGED }
            assertEquals(1, pressed, "expected exactly one MOUSE_PRESSED")
            assertEquals(1, released, "expected exactly one MOUSE_RELEASED")
            assertTrue(dragged > 0, "expected at least one MOUSE_DRAGGED, got $dragged")
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `modifier mask appears on the held key and does not leak to the next key`() {
        assumeLiveAwtAvailable()
        val target = JPanel().apply { preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX) }
        val frame = showFrameOnEdt(target)
        // Use a global AWT event listener instead of relying on focus transfer to the JPanel:
        // undecorated JFrames in tests are subject to window-manager focus quirks on macOS that
        // make `requestFocusInWindow` unreliable. The synthetic adapter dispatches KeyEvents to
        // the focus owner (or the root window if none), so a global listener catches both paths
        // without depending on which target won focus.
        val recorder = GlobalKeyEventRecorder().also { it.install() }
        try {
            val driver = RobotDriver.synthetic(frame.frame)
            // SHIFT+A then plain B. Modifier should be set on 'A' KEY_PRESSED and absent on 'B'.
            runBlocking {
                driver.pressKey(KeyEvent.VK_A, modifiers = KeyEvent.SHIFT_DOWN_MASK)
                driver.pressKey(KeyEvent.VK_B)
            }
            drainEdt()

            val aPressed =
                recorder.events.firstOrNull {
                    it.id == KeyEvent.KEY_PRESSED && it.keyCode == KeyEvent.VK_A
                }
            val bPressed =
                recorder.events.firstOrNull {
                    it.id == KeyEvent.KEY_PRESSED && it.keyCode == KeyEvent.VK_B
                }
            assertNotNull(aPressed, "expected a KEY_PRESSED for 'A'")
            assertNotNull(bPressed, "expected a KEY_PRESSED for 'B'")
            assertTrue(
                aPressed.modifiersEx and KeyEvent.SHIFT_DOWN_MASK != 0,
                "expected SHIFT_DOWN_MASK on 'A' KEY_PRESSED, got modifiersEx=${aPressed.modifiersEx}",
            )
            assertEquals(
                0,
                bPressed.modifiersEx and KeyEvent.SHIFT_DOWN_MASK,
                "expected no SHIFT_DOWN_MASK leak on 'B' KEY_PRESSED",
            )
        } finally {
            recorder.uninstall()
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `key events fall back to the last pointer target when AWT has no focus owner`() {
        assumeLiveAwtAvailable()
        val events: MutableList<KeyEvent> = CopyOnWriteArrayList()
        val target =
            JPanel().apply {
                preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
                addKeyListener(KeyEventRecorder(events))
            }
        val frame = showNonFocusableFrameOnEdt(target)
        try {
            assertTrue(
                frame.frame.focusOwner == null,
                "test fixture must model UIElement-style AWT with no focus owner",
            )
            val (cx, cy) = frame.targetCenterOnScreen(target)
            val driver = RobotDriver.synthetic(frame.frame)
            runBlocking {
                driver.click(cx, cy)
                driver.pressKey(KeyEvent.VK_A)
            }
            drainEdt()

            val pressed = events.firstOrNull {
                it.id == KeyEvent.KEY_PRESSED && it.keyCode == KeyEvent.VK_A
            }
            assertNotNull(
                pressed,
                "expected KEY_PRESSED to route to the last pointer target when no AWT focus owner exists",
            )
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `pasteText through the synthetic driver isolates and restores the fake clipboard`() {
        assumeLiveAwtAvailable()
        val target = JPanel().apply { preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX) }
        val frame = showFrameOnEdt(target)
        // See modifier test: a global AWT key listener avoids brittle focus-transfer assumptions
        // for tests that only need to observe dispatched keystrokes.
        val recorder = GlobalKeyEventRecorder().also { it.install() }
        try {
            val fakeClipboard = InMemoryClipboardAdapter(initial = "existing")
            // Build a RobotDriver manually so we can plug in the fake clipboard instead of
            // SystemClipboardAdapter. This is the canonical isolation strategy — no real
            // system clipboard is touched. Same module, internal constructor visible.
            val driver =
                RobotDriver(
                    robot = SyntheticRobotAdapter(frame.frame),
                    clipboard = fakeClipboard,
                    tccGuard = MacOsTccGuard.noop(),
                )
            runBlocking { driver.pasteText("hello") }
            drainEdt()

            // Clipboard contents restored to the pre-pasteText state.
            val restored =
                fakeClipboard.getContents()?.getTransferData(DataFlavor.stringFlavor) as? String
            assertEquals("existing", restored, "expected clipboard contents to be restored")

            // Observable paste keystroke: the VK_V KEY_PRESSED must carry the shortcut modifier
            // mask. Asserting only "modifier was pressed somewhere" + "V was pressed somewhere"
            // would pass even if the adapter degraded into "press modifier, release modifier,
            // then press plain V" — exactly the regression this parity test exists to catch.
            val expectedMask =
                if (detectMacOs()) KeyEvent.META_DOWN_MASK else KeyEvent.CTRL_DOWN_MASK
            val expectedModifierKey = if (detectMacOs()) KeyEvent.VK_META else KeyEvent.VK_CONTROL
            val vPressed =
                recorder.events.firstOrNull {
                    it.id == KeyEvent.KEY_PRESSED && it.keyCode == KeyEvent.VK_V
                }
            assertNotNull(vPressed, "expected a KEY_PRESSED for VK_V")
            assertTrue(
                vPressed.modifiersEx and expectedMask != 0,
                "expected VK_V KEY_PRESSED to carry the shortcut modifier mask " +
                    "(expectedMask=$expectedMask), got modifiersEx=${vPressed.modifiersEx}",
            )
            // Sanity: the modifier was pressed earlier in the sequence (so the mask above did
            // not sneak in from leftover state).
            val modifierPressedIndex =
                recorder.events.indexOfFirst {
                    it.id == KeyEvent.KEY_PRESSED && it.keyCode == expectedModifierKey
                }
            val vPressedIndex = recorder.events.indexOf(vPressed)
            assertTrue(
                modifierPressedIndex in 0 until vPressedIndex,
                "expected modifier (keyCode=$expectedModifierKey) KEY_PRESSED before VK_V; " +
                    "modifierPressedIndex=$modifierPressedIndex vPressedIndex=$vPressedIndex",
            )
        } finally {
            recorder.uninstall()
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `synthetic screenshot returns the requested dimensions and samples the rendered colour`() {
        assumeLiveAwtAvailable()
        val target = ColoredPanel(Color.RED)
        val frame = showFrameOnEdt(target)
        try {
            val driver = RobotDriver.synthetic(frame.frame)
            val origin = frame.targetTopLeftOnScreen(target)
            // Capture a sub-region inside the panel — keeps the test robust against frame
            // padding / WM decorations that could pollute pixels near the edges.
            val captureRegion =
                Rectangle(origin.x + 20, origin.y + 20, REGION_SIZE_PX, REGION_SIZE_PX)
            val image = driver.screenshot(captureRegion)
            assertEquals(REGION_SIZE_PX, image.width, "image width must match requested width")
            assertEquals(REGION_SIZE_PX, image.height, "image height must match requested height")
            // Sample a pixel inside the captured region. Synthetic capture paints the target via
            // Component.paint(Graphics); we expect the painted red to come through.
            val sampledRgb = image.getRGB(REGION_SIZE_PX / 2, REGION_SIZE_PX / 2)
            val sampled = Color(sampledRgb)
            assertEquals(
                Color.RED.rgb and 0x00FFFFFF,
                sampledRgb and 0x00FFFFFF,
                "expected red pixel; got $sampled",
            )
        } finally {
            disposeFrame(frame.frame)
        }
    }

    // --- Fixtures ---------------------------------------------------------------------------

    private fun showNonFocusableFrameOnEdt(target: JPanel): TestFrame =
        showFrameOnEdt(target) { focusableWindowState = false }

    private fun showFrameOnEdt(target: JPanel, configure: JFrame.() -> Unit = {}): TestFrame {
        var frame: JFrame? = null
        SwingUtilities.invokeAndWait {
            frame =
                JFrame("SyntheticInputParityTest").apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    isUndecorated = true
                    configure()
                    contentPane.layout = BorderLayout()
                    contentPane.add(target, BorderLayout.CENTER)
                    size = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
                    setLocation(FRAME_OFFSET_PX, FRAME_OFFSET_PX)
                    isVisible = true
                }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHOW_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && !target.isShowing) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        check(target.isShowing) { "Test JFrame did not become visible" }
        return TestFrame(frame!!)
    }

    private fun drainEdt() {
        // Drain twice: the first invokeAndWait flushes events already on the queue, the second
        // catches anything those events themselves posted (synthetic dispatch routes through
        // runOnEdt, and Compose-style listeners can repost).
        repeat(2) { SwingUtilities.invokeAndWait {} }
    }

    private fun disposeFrame(frame: JFrame) {
        // invokeLater (not invokeAndWait) so a wedged EDT — should never happen, but a
        // regression could trip the @Timeout — doesn't deadlock the cleanup path. The frame
        // is a daemon-thread JVM resource; it goes away with the JVM if dispose can't run now.
        SwingUtilities.invokeLater {
            frame.isVisible = false
            frame.dispose()
        }
    }

    private fun TestFrame.targetCenterOnScreen(target: JPanel): Pair<Int, Int> {
        var center = 0 to 0
        SwingUtilities.invokeAndWait {
            val origin = target.locationOnScreen
            center = (origin.x + target.width / 2) to (origin.y + target.height / 2)
        }
        return center
    }

    private fun TestFrame.targetTopLeftOnScreen(target: JPanel): java.awt.Point {
        var origin = java.awt.Point()
        SwingUtilities.invokeAndWait { origin = target.locationOnScreen }
        return origin
    }

    private class TestFrame(val frame: JFrame)

    private class WheelTargetPanel : JPanel(), MouseWheelListener {
        val events: MutableList<MouseWheelEvent> = CopyOnWriteArrayList()

        init {
            preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
            addMouseWheelListener(this)
        }

        override fun mouseWheelMoved(e: MouseWheelEvent) {
            events.add(e)
        }
    }

    private class MouseTargetPanel : JPanel(), MouseListener, MouseMotionListener {
        val events: MutableList<MouseEvent> = CopyOnWriteArrayList()

        init {
            preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
            addMouseListener(this)
            addMouseMotionListener(this)
        }

        override fun mouseClicked(e: MouseEvent) {
            events.add(e)
        }

        override fun mousePressed(e: MouseEvent) {
            events.add(e)
        }

        override fun mouseReleased(e: MouseEvent) {
            events.add(e)
        }

        override fun mouseEntered(e: MouseEvent) = Unit

        override fun mouseExited(e: MouseEvent) = Unit

        override fun mouseDragged(e: MouseEvent) {
            events.add(e)
        }

        override fun mouseMoved(e: MouseEvent) = Unit
    }

    private class KeyEventRecorder(private val events: MutableList<KeyEvent>) : KeyListener {
        override fun keyTyped(e: KeyEvent) {
            events.add(e)
        }

        override fun keyPressed(e: KeyEvent) {
            events.add(e)
        }

        override fun keyReleased(e: KeyEvent) {
            events.add(e)
        }
    }

    private class GlobalKeyEventRecorder {
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

    private class ColoredPanel(private val color: Color) : JPanel() {
        init {
            preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
            background = color
            isOpaque = true
        }
    }

    private class InMemoryClipboardAdapter(initial: String? = null) : ClipboardAdapter {
        private var current: Transferable? = initial?.let { StringSelection(it) }

        override val supportsRead: Boolean = true

        override fun getContents(): Transferable? = current

        override fun setContents(contents: Transferable) {
            current = contents
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS: Long = 15L
        const val SHOW_TIMEOUT_SECONDS: Long = 5L
        const val POLL_INTERVAL_MS: Long = 25L
        const val FRAME_SIZE_PX: Int = 200
        const val FRAME_OFFSET_PX: Int = 100
        const val REGION_SIZE_PX: Int = 60
    }
}

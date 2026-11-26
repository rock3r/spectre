package dev.sebastiano.spectre.core

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.awt.event.MouseMotionListener
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.AbstractAction
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
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
    fun `pointer verbs throw when no showing recipient exists and recover for the next click`() {
        assumeLiveAwtAvailable()
        val adapter = SyntheticRobotAdapter()
        assertEdtDeliveryFailure { adapter.mouseMove(-10_000, -10_000) }
        assertEdtDeliveryFailure { adapter.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK) }

        val target = MouseTargetPanel()
        val frame = showFrameOnEdt(target)
        try {
            val (cx, cy) = frame.targetCenterOnScreen(target)
            adapter.mouseMove(cx, cy)
            adapter.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            adapter.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            assertTrue(
                target.events.none { it.id == MouseEvent.MOUSE_DRAGGED },
                "a failed press must not poison the next pointer move or click",
            )
            assertTrue(target.events.any { it.id == MouseEvent.MOUSE_CLICKED })
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `failed modifier press does not modify the next delivered key`() {
        assumeLiveAwtAvailable()
        val adapter = SyntheticRobotAdapter()
        assertEdtDeliveryFailure { adapter.keyPress(KeyEvent.VK_CONTROL) }

        val events: MutableList<KeyEvent> = CopyOnWriteArrayList()
        val target =
            JPanel().apply {
                isFocusable = true
                addKeyListener(KeyEventRecorder(events))
            }
        val frame = showFrameOnEdt(target)
        try {
            focusOnEdt(frame.frame, target)
            adapter.keyPress(KeyEvent.VK_A)
            adapter.keyRelease(KeyEvent.VK_A)
            val pressed = events.first { it.id == KeyEvent.KEY_PRESSED }
            assertEquals(0, pressed.modifiersEx and KeyEvent.CTRL_DOWN_MASK)
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `focused Swing key binding works without an AWT KeyListener`() {
        assumeLiveAwtAvailable()
        var invocations = 0
        val target =
            JPanel().apply {
                isFocusable = true
                inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "invoke")
                actionMap.put(
                    "invoke",
                    object : AbstractAction() {
                        override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                            invocations++
                        }
                    },
                )
            }
        val frame = showFrameOnEdt(target)
        try {
            focusOnEdt(frame.frame, target)
            assertTrue(target.keyListeners.isEmpty())
            val adapter = SyntheticRobotAdapter(frame.frame)
            adapter.keyPress(KeyEvent.VK_A)
            adapter.keyRelease(KeyEvent.VK_A)
            assertEquals(1, invocations)
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `key listener lookup ignores a hidden focused-surface descendant`() {
        assumeLiveAwtAvailable()
        val hidden = JPanel().apply { addKeyListener(KeyEventRecorder(CopyOnWriteArrayList())) }
        val showing = JPanel().apply { addKeyListener(KeyEventRecorder(CopyOnWriteArrayList())) }
        val host =
            JPanel().apply {
                add(hidden)
                add(showing)
            }
        val frame = showFrameOnEdt(host)
        try {
            SwingUtilities.invokeAndWait { hidden.isVisible = false }
            assertEquals(showing, host.findKeyListenerSelfOrDescendant())
        } finally {
            disposeFrame(frame.frame)
        }
    }

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
    fun `frame fixture restores requested placement after a delayed native placement`() {
        assumeLiveAwtAvailable()
        val target = MouseTargetPanel()
        val delayedPlacementApplied = CountDownLatch(1)
        val frame =
            showFrameOnEdt(target) {
                addWindowListener(
                    object : WindowAdapter() {
                        override fun windowOpened(event: WindowEvent) {
                            // Model a WM acknowledging the map and applying its placement only
                            // after Swing has already made the target report isShowing=true.
                            SwingUtilities.invokeLater {
                                setLocation(DELAYED_FRAME_OFFSET_PX, DELAYED_FRAME_OFFSET_PX)
                                delayedPlacementApplied.countDown()
                            }
                        }
                    }
                )
            }
        try {
            assertTrue(
                delayedPlacementApplied.await(SHOW_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "controlled delayed placement was not applied",
            )
            assertEquals(
                FRAME_OFFSET_PX to FRAME_OFFSET_PX,
                frame.frameLocationOnScreen(),
                "the fixture must return only after the requested native placement is settled",
            )
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `moveTo emits MOUSE_MOVED without press release or click`() {
        assumeLiveAwtAvailable()
        val target = MouseTargetPanel()
        val frame = showFrameOnEdt(target)
        try {
            val (cx, cy) = frame.targetCenterOnScreen(target)
            val driver = RobotDriver.synthetic(frame.frame)
            runBlocking { driver.moveTo(cx, cy) }
            drainEdt()

            val types = target.events.map { it.id }
            assertTrue(types.contains(MouseEvent.MOUSE_MOVED), "expected MOUSE_MOVED, got $types")
            assertTrue(
                types.none {
                    it == MouseEvent.MOUSE_PRESSED ||
                        it == MouseEvent.MOUSE_RELEASED ||
                        it == MouseEvent.MOUSE_CLICKED ||
                        it == MouseEvent.MOUSE_DRAGGED
                },
                "moveTo must not press, release, click, or drag, got $types",
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
        val events: MutableList<KeyEvent> = CopyOnWriteArrayList()
        val target =
            JPanel().apply {
                preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
                isFocusable = true
                addKeyListener(KeyEventRecorder(events))
            }
        val frame = showFrameOnEdt(target)
        try {
            focusOnEdt(frame.frame, target)
            val driver = RobotDriver.synthetic(frame.frame)
            // SHIFT+A then plain B. Modifier should be set on 'A' KEY_PRESSED and absent on 'B'.
            runBlocking {
                driver.pressKey(KeyEvent.VK_A, modifiers = KeyEvent.SHIFT_DOWN_MASK)
                driver.pressKey(KeyEvent.VK_B)
            }
            drainEdt()

            val aPressed = events.firstOrNull {
                it.id == KeyEvent.KEY_PRESSED && it.keyCode == KeyEvent.VK_A
            }
            val bPressed = events.firstOrNull {
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
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `key events reject the last pointer target when AWT has no focus owner`() {
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
            runBlocking { driver.click(cx, cy) }
            assertEdtDeliveryFailure { runBlocking { driver.pressKey(KeyEvent.VK_A) } }
            assertTrue(events.isEmpty(), "no key event may be routed by pointer fallback")
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `key events do not descend from an unfocused pointer target`() {
        // Models the real Compose Desktop topology where the pointer target is a wrapper that
        // doesn't own the AWT KeyListener (e.g. `ComposeWindowPanel`) but a descendant does
        // (the inner Skiko canvas, where `ComposeSceneMediator` registers its key listener).
        //
        // To force the pointer hit-test to return the wrapper rather than `inner`, the inner
        // component is placed in a corner so the center of the wrapper is NOT covered by it.
        // The adapter must walk down from the wrapper to find the key-listening descendant for
        // text input to land where Compose actually consumes it.
        assumeLiveAwtAvailable()
        val innerEvents: MutableList<KeyEvent> = CopyOnWriteArrayList()
        val inner =
            JPanel().apply {
                isOpaque = true
                background = Color.BLUE
                // Sit in the top-left corner, leaving the wrapper's center uncovered so the
                // synthetic pointer hit-test resolves to the wrapper, not `inner`.
                setBounds(0, 0, FRAME_SIZE_PX / 4, FRAME_SIZE_PX / 4)
                addKeyListener(KeyEventRecorder(innerEvents))
            }
        val wrapper =
            JPanel(null).apply {
                preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
                add(inner)
            }
        val frame = showNonFocusableFrameOnEdt(wrapper)
        try {
            assertTrue(
                frame.frame.focusOwner == null,
                "test fixture must model UIElement-style AWT with no focus owner",
            )
            // Click the center of the wrapper, which is NOT inside `inner`'s bounds: this is the
            // scenario where the pointer target is the wrapper and the adapter has to descend.
            val (cx, cy) = frame.targetCenterOnScreen(wrapper)
            val driver = RobotDriver.synthetic(frame.frame)
            runBlocking { driver.click(cx, cy) }
            assertEdtDeliveryFailure { runBlocking { driver.pressKey(KeyEvent.VK_B) } }
            assertTrue(innerEvents.isEmpty(), "unfocused descendants must receive no keys")
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `pasteText through the synthetic driver isolates and restores the fake clipboard`() {
        assumeLiveAwtAvailable()
        val events: MutableList<KeyEvent> = CopyOnWriteArrayList()
        val target =
            JPanel().apply {
                preferredSize = Dimension(FRAME_SIZE_PX, FRAME_SIZE_PX)
                isFocusable = true
                addKeyListener(KeyEventRecorder(events))
            }
        val frame = showFrameOnEdt(target)
        try {
            focusOnEdt(frame.frame, target)
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
            val vPressed = events.firstOrNull {
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
            val modifierPressedIndex = events.indexOfFirst {
                it.id == KeyEvent.KEY_PRESSED && it.keyCode == expectedModifierKey
            }
            val vPressedIndex = events.indexOf(vPressed)
            assertTrue(
                modifierPressedIndex in 0 until vPressedIndex,
                "expected modifier (keyCode=$expectedModifierKey) KEY_PRESSED before VK_V; " +
                    "modifierPressedIndex=$modifierPressedIndex vPressedIndex=$vPressedIndex",
            )
        } finally {
            disposeFrame(frame.frame)
        }
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `synthetic screenshot uses framebuffer capture for the requested dimensions`() {
        assumeLiveAwtAvailable()
        val target = ColoredPanel(Color.RED)
        // isAlwaysOnTop: on Windows a JVM spawned by Gradle/IntelliJ can't raise its window past
        // the foreground terminal with toFront() alone (foreground-stealing prevention), so a real
        // framebuffer capture at the window coords reads the terminal/desktop pixels instead of the
        // panel. showFrameOnEdt raises always-on-top frames to front once realised. Mirrors the
        // established real-Robot Windows pattern — see WindowsRobotSmoke.
        val frame = showFrameOnEdt(target) { isAlwaysOnTop = true }
        try {
            val origin = frame.targetTopLeftOnScreen(target)
            // Capture a sub-region inside the panel — keeps the test robust against frame
            // padding / WM decorations that could pollute pixels near the edges.
            val captureRegion =
                Rectangle(origin.x + 20, origin.y + 20, REGION_SIZE_PX, REGION_SIZE_PX)
            // Even when frontmost, a locked console / RDP-VNC session with no on-screen framebuffer
            // / occluded worker can show the window yet never composite it into the captured pixels
            // — the exact failure this hit on a non-interactive Windows box (capture returned the
            // desktop's dark-grey background, not red). assumeLiveAwtAvailable()'s headless check
            // doesn't catch that. Probe on-screen capture independently of the driver and skip
            // there; a genuine capture-path regression (probe sees red, driver doesn't) still fails
            // below rather than being masked as a skip.
            assumeOnScreenCaptureReflects(captureRegion, Color.RED)
            val driver = RobotDriver.synthetic(frame.frame)
            val image = waitForScreenshotColor(driver, captureRegion, Color.RED)
            // java.awt.Robot.createScreenCapture returns an image sized to the *requested logical*
            // rectangle regardless of display DPI scaling: verified on Windows at forced
            // sun.java2d.uiScale=2, where a 60×60 logical request still yields a 60×60 image (the
            // 2× device pixels surface only via createMultiResolutionScreenCapture). So
            // REGION_SIZE_PX is the correct expectation on HiDPI too — no scale factor applies.
            assertEquals(REGION_SIZE_PX, image.width, "image width must match requested width")
            assertEquals(REGION_SIZE_PX, image.height, "image height must match requested height")
            // Sample the captured image's own centre rather than a hardcoded offset, so the pixel
            // assertion stays correct even if a capture ever returns a differently sized image.
            // Synthetic input still uses real framebuffer capture, so the red panel comes through.
            val sampledRgb = image.getRGB(image.width / 2, image.height / 2)
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
                    // A frame that opted into always-on-top is a screen-capture target and must be
                    // the frontmost, composited surface for a real framebuffer read to see its
                    // pixels. Raise it now that it's realised — toFront() before isVisible is a
                    // no-op. Synthetic-input-only frames skip this: they dispatch AWT events
                    // straight into the hierarchy and don't depend on OS z-order.
                    if (isAlwaysOnTop) toFront()
                }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHOW_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && !target.isShowing) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        check(target.isShowing) { "Test JFrame did not become visible" }
        val shownFrame = checkNotNull(frame)
        awaitNativeFramePlacement(shownFrame, target)
        return TestFrame(shownFrame)
    }

    private fun awaitNativeFramePlacement(frame: JFrame, target: JPanel) {
        // isShowing becomes true as soon as AWT maps the peer, before a reparenting WM has
        // necessarily sent its final ConfigureNotify. Let already-queued map callbacks run, then
        // re-assert the requested location. On X11 that ConfigureRequest is ordered after the map;
        // Toolkit.sync plus locationOnScreen read-back observes the native acknowledgement rather
        // than trusting JFrame's pre-map coordinates.
        drainEdt()
        var matchingObservations = 0
        var placementMatches = false
        var lastObserved = "not observed"
        val observations = ArrayDeque<String>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHOW_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && matchingObservations < SETTLED_OBSERVATIONS) {
            SwingUtilities.invokeAndWait {
                frame.setLocation(FRAME_OFFSET_PX, FRAME_OFFSET_PX)
                Toolkit.getDefaultToolkit().sync()
                val location = frame.locationOnScreen
                lastObserved =
                    "screen=(${location.x},${location.y}) frame=(${frame.x},${frame.y}) " +
                        "showing=${target.isShowing}"
                placementMatches =
                    location.x == FRAME_OFFSET_PX &&
                        location.y == FRAME_OFFSET_PX &&
                        target.isShowing
            }
            if (observations.size == MAX_PLACEMENT_DIAGNOSTICS) observations.removeFirst()
            observations.addLast(lastObserved)
            matchingObservations = if (placementMatches) matchingObservations + 1 else 0
            if (matchingObservations < SETTLED_OBSERVATIONS) Thread.sleep(POLL_INTERVAL_MS)
        }
        check(matchingObservations == SETTLED_OBSERVATIONS) {
            "Test JFrame native placement did not settle at " +
                "($FRAME_OFFSET_PX,$FRAME_OFFSET_PX) within ${SHOW_TIMEOUT_SECONDS}s; " +
                "last=$lastObserved recent=${observations.joinToString()}"
        }
    }

    private fun waitForScreenshotColor(
        driver: RobotDriver,
        region: Rectangle,
        expected: Color,
    ): BufferedImage {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHOW_TIMEOUT_SECONDS)
        var latest = driver.screenshot(region)
        val expectedRgb = expected.rgb and 0x00FFFFFF
        while (System.nanoTime() < deadline && latest.centerRgb() != expectedRgb) {
            Thread.sleep(POLL_INTERVAL_MS)
            latest = driver.screenshot(region)
        }
        return latest
    }

    private fun BufferedImage.centerRgb(): Int = getRGB(width / 2, height / 2) and 0x00FFFFFF

    private fun drainEdt() {
        // Drain twice: the first invokeAndWait flushes events already on the queue, the second
        // catches anything those events themselves posted (synthetic dispatch routes through
        // runOnEdt, and Compose-style listeners can repost).
        repeat(2) { SwingUtilities.invokeAndWait {} }
    }

    private fun focusOnEdt(frame: JFrame, target: JPanel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHOW_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline && frame.focusOwner !== target) {
            SwingUtilities.invokeAndWait {
                frame.toFront()
                frame.requestFocus()
                target.requestFocus()
            }
            drainEdt()
            if (frame.focusOwner !== target) Thread.sleep(POLL_INTERVAL_MS)
        }
        assumeTrue(
            frame.focusOwner === target,
            "the headed environment did not grant the test frame AWT focus",
        )
    }

    private fun assertEdtDeliveryFailure(block: () -> Unit) {
        val failure = assertFailsWith<InvocationTargetException>(block = block)
        assertTrue(failure.targetException is IllegalStateException)
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

    private fun TestFrame.frameLocationOnScreen(): Pair<Int, Int> {
        var location = 0 to 0
        SwingUtilities.invokeAndWait {
            val origin = frame.locationOnScreen
            location = origin.x to origin.y
        }
        return location
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

        override fun mouseMoved(e: MouseEvent) {
            events.add(e)
        }
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
        const val DELAYED_FRAME_OFFSET_PX: Int = 20
        const val REGION_SIZE_PX: Int = 60
        const val SETTLED_OBSERVATIONS: Int = 4
        const val MAX_PLACEMENT_DIAGNOSTICS: Int = 12
    }
}

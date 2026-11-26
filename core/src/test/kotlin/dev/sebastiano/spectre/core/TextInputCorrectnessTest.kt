package dev.sebastiano.spectre.core

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TextInputCorrectnessTest {
    @Test
    fun `real OS typeText rejects letters when Caps Lock state is unknown before dispatch`() =
        runTest {
            val robot = TextRobot(deliversRealOsInput = true, capsLock = null)
            val driver = RobotDriver(robot, TextClipboard())

            val failure = assertFailsWith<IllegalStateException> { driver.typeText("a") }

            assertTrue(failure.message.orEmpty().contains("Caps Lock"))
            assertEquals(emptyList(), robot.events)
        }

    @Test
    fun `real OS typeText permits empty digits and punctuation when Caps Lock is unknown`() =
        runTest {
            val robot = TextRobot(deliversRealOsInput = true, capsLock = null)
            val driver = RobotDriver(robot, TextClipboard())

            driver.typeText("")
            driver.typeText("19!?")

            assertTrue(robot.events.isNotEmpty())
        }

    @Test
    fun `synthetic typeText has deterministic letter case when Caps Lock is unknown`() = runTest {
        val robot = TextRobot(capsLock = null)
        val driver = RobotDriver(robot, TextClipboard())

        driver.typeText("aA")

        assertEquals(
            listOf(
                "press(${KeyEvent.VK_A})",
                "release(${KeyEvent.VK_A})",
                "press(${KeyEvent.VK_SHIFT})",
                "press(${KeyEvent.VK_A})",
                "release(${KeyEvent.VK_A})",
                "release(${KeyEvent.VK_SHIFT})",
            ),
            robot.events,
        )
    }

    @Test
    fun `pasteText timeout throws before dispatch and restores previous clipboard`() = runTest {
        val clipboard = TextClipboard(StringSelection("previous"), neverSettle = true)
        val robot = TextRobot()
        val driver = RobotDriver(robot, clipboard)

        val failure = assertFailsWith<IllegalStateException> { driver.pasteText("spectre") }

        assertTrue(failure.message.orEmpty().contains("did not settle"))
        assertEquals(emptyList(), robot.events)
        assertEquals("previous", clipboard.stringContents())
    }

    @Test
    fun `pasteText reports clipboard save failure without writing or dispatching`() = runTest {
        val clipboard = TextClipboard(readFailure = IllegalStateException("read failed"))
        val robot = TextRobot()
        val driver = RobotDriver(robot, clipboard)

        val failure = assertFailsWith<IllegalStateException> { driver.pasteText("spectre") }

        assertTrue(failure.message.orEmpty().contains("save"))
        assertEquals(0, clipboard.setCalls)
        assertEquals(emptyList(), robot.events)
    }

    @Test
    fun `pasteText preserves primary failure and suppresses clipboard restore failure`() = runTest {
        val primary = IllegalStateException("paste dispatch failed")
        val restore = IllegalStateException("restore failed")
        val robot = TextRobot(keyPressFailure = primary)
        val clipboard =
            TextClipboard(StringSelection("previous"), failSetAt = 2, setFailure = restore)
        val driver = RobotDriver(robot, clipboard)

        val failure = assertFailsWith<IllegalStateException> { driver.pasteText("spectre") }

        assertSame(primary, failure)
        assertEquals(listOf(restore), failure.suppressed.toList())
    }

    @Test
    fun `pasteText restores exact previous transferable ownership`() = runTest {
        val previous = StringSelection("owned value")
        val clipboard = TextClipboard(previous)
        val driver = RobotDriver(TextRobot(), clipboard)

        driver.pasteText("spectre")

        assertSame(previous, clipboard.current)
    }

    @Test
    fun `pasteText distinguishes and restores a genuinely empty clipboard`() = runTest {
        val clipboard = TextClipboard()
        val driver = RobotDriver(TextRobot(), clipboard)

        driver.pasteText("spectre")

        assertTrue(clipboard.current?.transferDataFlavors?.isEmpty() == true)
    }
}

private class TextRobot(
    override val deliversRealOsInput: Boolean = false,
    private val capsLock: Boolean? = false,
    private val keyPressFailure: Throwable? = null,
) : RobotAdapter {
    override val autoDelayMs: Int = 0
    override val requiresOffEdt: Boolean = false
    val events = mutableListOf<String>()

    override fun mouseMove(x: Int, y: Int) = Unit

    override fun mousePress(buttons: Int) = Unit

    override fun mouseRelease(buttons: Int) = Unit

    override fun mouseWheel(wheelClicks: Int) = Unit

    override fun waitForIdle() = Unit

    override fun getLockingKeyState(keyCode: Int): Boolean? = capsLock

    override fun keyPress(keyCode: Int) {
        keyPressFailure?.let { throw it }
        events += "press($keyCode)"
    }

    override fun keyRelease(keyCode: Int) {
        events += "release($keyCode)"
    }
}

private class TextClipboard(
    initial: Transferable? = null,
    private val neverSettle: Boolean = false,
    private val readFailure: Throwable? = null,
    private val failSetAt: Int = Int.MAX_VALUE,
    private val setFailure: Throwable = IllegalStateException("set failed"),
) : ClipboardAdapter {
    var current: Transferable? = initial
        private set

    private var pending: Transferable? = null
    var setCalls: Int = 0
        private set

    override fun getContents(): Transferable? {
        readFailure?.let { throw it }
        return current
    }

    override fun setContents(contents: Transferable) {
        setCalls++
        if (setCalls == failSetAt) throw setFailure
        if (neverSettle && setCalls == 1) pending = contents else current = contents
    }

    fun stringContents(): String? =
        current
            ?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
            ?.let { it.getTransferData(DataFlavor.stringFlavor) as String }
}

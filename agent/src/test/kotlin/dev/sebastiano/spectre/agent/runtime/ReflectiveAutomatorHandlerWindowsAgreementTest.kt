@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import java.awt.Dialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

/**
 * #362 windows()/allNodes() agreement at the reflective mapping seam: Dialog titles, windowTitle
 * property, and bounds failures must not drop tracked surfaces from the Windows wire response.
 */
class ReflectiveAutomatorHandlerWindowsAgreementTest {

    @Test
    fun `Windows op extracts title from Dialog and windowTitle property`() {
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Dialog() requires a non-headless JVM; skipped on Linux CI.",
        )
        val dialog = Dialog(null as Frame?, "Dialog Title")
        try {
            val viaGetWindow =
                AgreementFakeTrackedWindow(
                    surfaceIdValue = "dialog-surface",
                    isPopupValue = true,
                    composeSurfaceBoundsOnScreenValue = Rectangle(1, 2, 3, 4),
                    windowValue = dialog,
                )
            val viaWindowTitle =
                AgreementFakeTrackedWindowWithTitle(
                    surfaceIdValue = "titled-surface",
                    isPopupValue = false,
                    composeSurfaceBoundsOnScreenValue = Rectangle(5, 6, 7, 8),
                    windowTitleValue = "From windowTitle",
                )
            val automator =
                AgreementFakeAutomator(windowsValue = listOf(viaGetWindow, viaWindowTitle))
            val handler = ReflectiveAutomatorHandler(automator)

            val response = handler.handle(AgentRequest.Windows)
            check(response is AgentResponse.Windows)
            assertEquals(2, response.windows.size)
            assertEquals("Dialog Title", response.windows[0].title)
            assertEquals("dialog-surface", response.windows[0].surfaceId)
            assertEquals("From windowTitle", response.windows[1].title)
            assertEquals("titled-surface", response.windows[1].surfaceId)
        } finally {
            dialog.dispose()
        }
    }

    @Test
    fun `Windows op keeps surface when composeSurfaceBoundsOnScreen throws`() {
        val trackedWindow =
            AgreementFakeTrackedWindowThrowingBounds(
                surfaceIdValue = "window:0",
                isPopupValue = false,
                windowTitleValue = "Still listed",
            )
        val automator = AgreementFakeAutomator(windowsValue = listOf(trackedWindow))
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.Windows)
        check(response is AgentResponse.Windows) {
            "expected AgentResponse.Windows, got ${response::class.simpleName}: $response"
        }
        assertEquals(1, response.windows.size)
        val dto = response.windows.single()
        assertEquals("window:0", dto.surfaceId)
        assertEquals("Still listed", dto.title)
        assertEquals(false, dto.isPopup)
        assertEquals(0, dto.bounds.width)
        assertEquals(0, dto.bounds.height)
        assertTrue(dto.surfaceId.startsWith("window:"))
    }

    @Test
    fun `Windows op reports isShowing from the AWT window`() {
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Frame() requires a non-headless JVM; skipped on Linux CI.",
        )
        val frame = Frame("showing-probe")
        try {
            // Not shown yet — isShowing should be false after peer creation via pack().
            frame.pack()
            assertEquals(false, frame.isShowing)
            val tracked =
                AgreementFakeTrackedWindow(
                    surfaceIdValue = "window:0",
                    isPopupValue = false,
                    composeSurfaceBoundsOnScreenValue = Rectangle(0, 0, 100, 80),
                    windowValue = frame,
                )
            val handler =
                ReflectiveAutomatorHandler(AgreementFakeAutomator(windowsValue = listOf(tracked)))
            val response = handler.handle(AgentRequest.Windows)
            check(response is AgentResponse.Windows)
            assertEquals(false, response.windows.single().isShowing)

            frame.isVisible = true
            val shown =
                AgreementFakeTrackedWindow(
                    surfaceIdValue = "window:0",
                    isPopupValue = false,
                    composeSurfaceBoundsOnScreenValue = Rectangle(0, 0, 100, 80),
                    windowValue = frame,
                )
            val shownResponse =
                ReflectiveAutomatorHandler(AgreementFakeAutomator(windowsValue = listOf(shown)))
                    .handle(AgentRequest.Windows)
            check(shownResponse is AgentResponse.Windows)
            assertEquals(true, shownResponse.windows.single().isShowing)
        } finally {
            frame.dispose()
        }
    }
}

private class AgreementFakeTrackedWindow(
    private val surfaceIdValue: String,
    private val isPopupValue: Boolean,
    private val composeSurfaceBoundsOnScreenValue: Rectangle,
    private val windowValue: java.awt.Window?,
) {
    @Suppress("unused") fun getSurfaceId(): String = surfaceIdValue

    @Suppress("unused") fun isPopup(): Boolean = isPopupValue

    @Suppress("unused")
    fun getComposeSurfaceBoundsOnScreen(): Rectangle = composeSurfaceBoundsOnScreenValue

    @Suppress("unused") fun getWindow(): java.awt.Window? = windowValue
}

private class AgreementFakeTrackedWindowWithTitle(
    private val surfaceIdValue: String,
    private val isPopupValue: Boolean,
    private val composeSurfaceBoundsOnScreenValue: Rectangle,
    private val windowTitleValue: String?,
) {
    @Suppress("unused") fun getSurfaceId(): String = surfaceIdValue

    @Suppress("unused") fun isPopup(): Boolean = isPopupValue

    @Suppress("unused")
    fun getComposeSurfaceBoundsOnScreen(): Rectangle = composeSurfaceBoundsOnScreenValue

    @Suppress("unused") fun getWindowTitle(): String? = windowTitleValue

    @Suppress("unused", "FunctionOnlyReturningConstant") fun getWindow(): java.awt.Window? = null
}

private class AgreementFakeTrackedWindowThrowingBounds(
    private val surfaceIdValue: String,
    private val isPopupValue: Boolean,
    private val windowTitleValue: String?,
) {
    @Suppress("unused") fun getSurfaceId(): String = surfaceIdValue

    @Suppress("unused") fun isPopup(): Boolean = isPopupValue

    @Suppress("unused")
    fun getComposeSurfaceBoundsOnScreen(): Rectangle {
        error("simulated locationOnScreen failure")
    }

    @Suppress("unused") fun getWindowTitle(): String? = windowTitleValue

    @Suppress("unused", "FunctionOnlyReturningConstant") fun getWindow(): java.awt.Window? = null
}

private class AgreementFakeAutomator(private val windowsValue: List<Any>) {
    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = windowsValue

    @Suppress("unused") fun allNodes(): List<Any> = emptyList()

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()
}

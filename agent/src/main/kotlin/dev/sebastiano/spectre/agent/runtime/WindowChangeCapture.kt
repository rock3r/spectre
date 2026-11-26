package dev.sebastiano.spectre.agent.runtime

import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

/** Agent-owned observation, independent of the target core version. */
internal class WindowChangeCapture private constructor() : AutoCloseable {
    private val generation = AtomicLong()
    private val observed = mutableSetOf<Window>()
    private val propertyListener = PropertyChangeListener(::propertyChanged)
    private val awtListener = AWTEventListener(::eventDispatched)

    val changeGeneration: Long
        get() = onEdt { generation.get() }

    private fun propertyChanged(event: PropertyChangeEvent) {
        if (
            event.source is Window &&
                (event.propertyName == "title" || event.propertyName == "owner")
        ) {
            generation.incrementAndGet()
        }
    }

    private fun eventDispatched(event: AWTEvent) {
        val window = event.source as? Window ?: return
        when (event) {
            is WindowEvent -> if (event.id in RELEVANT_WINDOW_EVENTS) generation.incrementAndGet()
            is ComponentEvent ->
                if (
                    event.id == ComponentEvent.COMPONENT_SHOWN ||
                        event.id == ComponentEvent.COMPONENT_HIDDEN
                ) {
                    generation.incrementAndGet()
                }
        }
        observe(window)
    }

    private fun observe(window: Window) {
        if (observed.add(window)) window.addPropertyChangeListener(propertyListener)
    }

    override fun close() = onEdt {
        Toolkit.getDefaultToolkit().removeAWTEventListener(awtListener)
        observed.forEach { it.removePropertyChangeListener(propertyListener) }
        observed.clear()
    }

    companion object {
        fun begin(): WindowChangeCapture = onEdt {
            WindowChangeCapture().also { capture ->
                Window.getWindows().forEach(capture::observe)
                Toolkit.getDefaultToolkit()
                    .addAWTEventListener(
                        capture.awtListener,
                        AWTEvent.WINDOW_EVENT_MASK or AWTEvent.COMPONENT_EVENT_MASK,
                    )
            }
        }

        private val RELEVANT_WINDOW_EVENTS =
            setOf(WindowEvent.WINDOW_OPENED, WindowEvent.WINDOW_CLOSING, WindowEvent.WINDOW_CLOSED)
    }
}

private fun <T> onEdt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return requireNotNull(result).getOrThrow()
}

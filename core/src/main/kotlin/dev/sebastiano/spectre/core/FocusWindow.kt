package dev.sebastiano.spectre.core

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import javax.swing.Timer

internal fun requestAndVerifyWindowFocus(window: Window) {
    val focused = CountDownLatch(1)
    val listener =
        object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) = focused.countDown()
        }
    val request = {
        check(window.isShowing && window.isFocusableWindow) {
            "Spectre can only request focus for a showing and focusable target window."
        }
        window.addWindowFocusListener(listener)
        requestApplicationForegroundOnMac()
        window.toFront()
        window.requestFocus()
        if (window.isFocused) focused.countDown()
    }
    try {
        onEdt(request)
        val focusArrived =
            if (SwingUtilities.isEventDispatchThread()) {
                awaitFocusOnEdt(focused)
            } else {
                focused.await(FOCUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        check(focusArrived || onEdt { window.isFocused }) {
            "Spectre requested focus for the target window, but it did not become focused " +
                "within ${FOCUS_TIMEOUT_MS}ms."
        }
    } finally {
        onEdt { window.removeWindowFocusListener(listener) }
    }
}

private fun requestApplicationForegroundOnMac() {
    if (!detectMacOs() || !Desktop.isDesktopSupported()) return

    val desktop = Desktop.getDesktop()
    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) {
        desktop.requestForeground(true)
    }
}

private fun awaitFocusOnEdt(focused: CountDownLatch): Boolean {
    val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FOCUS_TIMEOUT_MS)
    val timer =
        Timer(FOCUS_POLL_INTERVAL_MS) {
            if (focused.count == 0L || System.nanoTime() >= deadline) loop.exit()
        }
    timer.start()
    try {
        loop.enter()
    } finally {
        timer.stop()
    }
    return focused.count == 0L
}

private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()

    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
}

private const val FOCUS_TIMEOUT_MS: Long = 2_000
private const val FOCUS_POLL_INTERVAL_MS: Int = 10

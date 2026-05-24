package dev.sebastiano.spectre.core

import javax.swing.SwingUtilities

internal fun <T> readOnEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()

    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return requireNotNull(result) {
            "SwingUtilities.invokeAndWait returned without the EDT block setting the result"
        }
        .getOrThrow()
}

@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

/**
 * Structural fingerprint of every tracked Compose surface: the value [ComposeAutomator.waitForIdle]
 * samples and watches for stability over its quiet period.
 *
 * The fingerprint covers what a semantics-level idle barrier cares about — surface identity, node
 * identity and count, layout bounds, role, focus/disabled/selected flags, and hashes of text,
 * content descriptions, and editable text. Anything not in here is invisible to `waitForIdle`;
 * pixels that change without changing semantics are [ComposeAutomator.waitForVisualIdle]'s job.
 *
 * The exact encoding is an implementation detail — only equality between two consecutive samples is
 * meaningful, never the string itself. Callers must read on the EDT (semantics access requires it)
 * and refresh the tracked-window set first, so a surface that appeared or vanished is part of the
 * sample rather than answered from a stale snapshot.
 */
internal fun uiFingerprint(windows: List<TrackedWindow>, semanticsReader: SemanticsReader): String =
    buildString {
        for (window in windows) {
            append(window.surfaceId)
            append('|')
            val nodes = semanticsReader.readAllNodes(listOf(window))
            append(nodes.size)
            append('|')
            for (node in nodes) {
                appendNodeFingerprint(node)
            }
            append("||")
        }
    }

private fun StringBuilder.appendNodeFingerprint(node: AutomatorNode) {
    append(node.key.toString())
    val bounds = node.boundsInWindow
    append('@')
    append(bounds.left.toBits())
    append(',')
    append(bounds.top.toBits())
    append(',')
    append(bounds.right.toBits())
    append(',')
    append(bounds.bottom.toBits())
    node.role?.let {
        append(':')
        append(it.toString())
    }
    if (node.isFocused) append(":F")
    if (node.isDisabled) append(":D")
    if (node.isSelected) append(":S")
    if (node.texts.isNotEmpty()) {
        append(":T")
        append(node.texts.joinToString(separator = "").hashCode())
    }
    if (node.contentDescriptions.isNotEmpty()) {
        append(":C")
        append(node.contentDescriptions.joinToString(separator = "").hashCode())
    }
    node.editableText?.let {
        append(":E")
        append(it.hashCode())
    }
    append(';')
}

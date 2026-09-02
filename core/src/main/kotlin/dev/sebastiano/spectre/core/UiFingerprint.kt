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
        append(fingerprintHashOf(node.texts))
    }
    if (node.contentDescriptions.isNotEmpty()) {
        append(":C")
        append(fingerprintHashOf(node.contentDescriptions))
    }
    node.editableText?.let {
        append(":E")
        append(it.hashCode())
    }
    append(';')
}

/**
 * Collapses a node's text or content-description list into the hash [uiFingerprint] embeds.
 *
 * The separator is load-bearing. Joining with nothing makes `["ab", "c"]` and `["a", "bc"]` hash
 * identically, so re-segmented text would read as an unchanged fingerprint and let
 * [ComposeAutomator.waitForIdle] return on a UI that actually did change. U+0001 is a control
 * character that cannot occur in rendered UI text, so it can never collide with content. It is
 * written as an escape rather than a raw control byte so it stays visible to anyone reading,
 * copying, or reviewing this line — as a raw byte it once went missing in a refactor unnoticed.
 */
internal fun fingerprintHashOf(values: List<String>): Int =
    values.joinToString(separator = "\u0001").hashCode()

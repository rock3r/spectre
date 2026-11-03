package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto

/**
 * One human-readable line per node for `tree`, `find`, and `find-text` without `--json`.
 *
 * The line is built to be grepped: `<key> <role> tag="…" text="…" editable="…" desc="…" toggle=…
 * progress=… steps=… parent=… [flags] @ (x,y wxh)`. Empty values are left out, and quoted values
 * escape quotes, backslashes, and line breaks so each node stays on one line.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun formatNodeLine(node: NodeSnapshotDto): String = buildString {
    append(node.key)
    append(' ')
    append(node.role ?: "Node")
    node.testTag?.takeIf { it.isNotEmpty() }?.let { appendQuoted("tag", it) }
    node.texts.filter { it.isNotEmpty() }.forEach { appendQuoted("text", it) }
    node.editableText?.takeIf { it.isNotEmpty() }?.let { appendQuoted("editable", it) }
    node.contentDescriptions
        .ifEmpty { listOfNotNull(node.contentDescription) }
        .filter { it.isNotEmpty() }
        .forEach { appendQuoted("desc", it) }
    node.toggleableState?.let { append(" toggle=$it") }
    node.progress?.let { progress ->
        append(" progress=${progress.current.compact()}/")
        append("${progress.min.compact()}..${progress.max.compact()}")
        if (progress.steps > 0) append(" steps=${progress.steps}")
    }
    node.parentKey?.let { append(" parent=$it") }
    if (node.isFocused) append(" [focused]")
    if (node.isDisabled) append(" [disabled]")
    if (node.isSelected) append(" [selected]")
    if (node.isClickable) append(" [clickable]")
    if (!node.isVisible) append(" [hidden]")
    val bounds = node.bounds
    append(" @ (${bounds.x},${bounds.y} ${bounds.width}x${bounds.height})")
}

private fun StringBuilder.appendQuoted(label: String, value: String) {
    append(' ')
    append(label)
    append("=\"")
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(char)
        }
    }
    append('"')
}

/** `1.0` prints as `1`, so whole-number ranges such as `0..1` stay short. */
private fun Float.compact(): String =
    if (isFinite() && this == toLong().toFloat()) toLong().toString() else toString()

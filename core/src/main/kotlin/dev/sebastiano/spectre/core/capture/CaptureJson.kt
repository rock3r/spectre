package dev.sebastiano.spectre.core.capture

/**
 * Deterministic JSON encoder for [CaptureDocument].
 *
 * Key order and formatting are part of the golden contract. Prefer this over ad-hoc serializers so
 * agents and `jq` recipes see a stable document shape.
 */
public object CaptureJson {

    public fun encode(document: CaptureDocument): String = buildString {
        appendLine("{")
        appendLine("""  "schemaVersion": ${document.schemaVersion},""")
        appendLine("""  "capturedAt": ${jsonString(document.capturedAt)},""")
        appendLine("""  "window": {""")
        appendWindowBody(document.window, indent = "    ")
        appendLine("""  },""")
        appendLine("""  "nodes": [""")
        document.nodes.forEachIndexed { index, node ->
            appendNode(node, indent = "    ", trailingComma = index < document.nodes.lastIndex)
        }
        appendLine("""  ],""")
        appendLine("""  "summary": {""")
        appendSummaryBody(document.summary, indent = "    ")
        appendLine("""  }""")
        append("}")
        appendLine()
    }

    private fun StringBuilder.appendWindowBody(window: CaptureWindow, indent: String) {
        appendLine("""$indent"index": ${window.index},""")
        appendLine("""$indent"surfaceId": ${jsonString(window.surfaceId)},""")
        appendLine("""$indent"title": ${jsonStringOrNull(window.title)},""")
        appendLine("""$indent"isPopup": ${window.isPopup},""")
        appendLine("""$indent"boundsScreen": {""")
        appendRectBody(window.boundsScreen, indent = "$indent  ")
        appendLine("""$indent},""")
        appendLine("""$indent"densityScaleX": ${formatDouble(window.densityScaleX)},""")
        appendLine("""$indent"densityScaleY": ${formatDouble(window.densityScaleY)},""")
        appendLine("""$indent"imageWidth": ${window.imageWidth},""")
        appendLine("""$indent"imageHeight": ${window.imageHeight}""")
    }

    private fun StringBuilder.appendNode(
        node: CaptureNode,
        indent: String,
        trailingComma: Boolean,
    ) {
        appendLine("""$indent{""")
        val inner = "$indent  "
        appendLine("""$inner"key": ${jsonString(node.key)},""")
        appendLine("""$inner"testTag": ${jsonStringOrNull(node.testTag)},""")
        appendLine("""$inner"text": ${jsonStringOrNull(node.text)},""")
        appendLine("""$inner"texts": [""")
        node.texts.forEachIndexed { index, value ->
            val line = """$inner  ${jsonString(value)}"""
            if (index < node.texts.lastIndex) appendLine("$line,") else appendLine(line)
        }
        appendLine("""$inner],""")
        appendLine("""$inner"contentDescription": ${jsonStringOrNull(node.contentDescription)},""")
        appendLine("""$inner"role": ${jsonStringOrNull(node.role)},""")
        appendLine("""$inner"enabled": ${node.enabled},""")
        appendLine("""$inner"clickable": ${node.clickable},""")
        appendLine("""$inner"focused": ${node.focused},""")
        appendLine("""$inner"selected": ${node.selected},""")
        appendLine("""$inner"boundsImage": {""")
        appendRectBody(node.boundsImage, indent = "$inner  ")
        appendLine("""$inner},""")
        appendLine("""$inner"boundsScreen": {""")
        appendRectBody(node.boundsScreen, indent = "$inner  ")
        appendLine("""$inner}""")
        if (trailingComma) appendLine("""$indent},""") else appendLine("""$indent}""")
    }

    private fun StringBuilder.appendSummaryBody(summary: CaptureSummary, indent: String) {
        appendLine("""$indent"nodeCount": ${summary.nodeCount},""")
        appendLine("""$indent"taggedNodeCount": ${summary.taggedNodeCount},""")
        appendLine("""$indent"textedNodeCount": ${summary.textedNodeCount},""")
        appendLine("""$indent"imageWidth": ${summary.imageWidth},""")
        appendLine("""$indent"imageHeight": ${summary.imageHeight},""")
        appendLine("""$indent"captureDurationMs": ${summary.captureDurationMs}""")
    }

    private fun StringBuilder.appendRectBody(rect: CaptureRect, indent: String) {
        appendLine("""$indent"x": ${rect.x},""")
        appendLine("""$indent"y": ${rect.y},""")
        appendLine("""$indent"width": ${rect.width},""")
        appendLine("""$indent"height": ${rect.height}""")
    }

    private fun jsonString(value: String): String = "\"${escape(value)}\""

    private fun jsonStringOrNull(value: String?): String =
        if (value == null) "null" else jsonString(value)

    private fun escape(value: String): String =
        buildString(value.length + ESCAPE_CAPACITY_SLACK) {
            for (ch in value) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (ch.code < FIRST_PRINTABLE_ASCII) {
                            append("\\u")
                            append(ch.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, '0'))
                        } else {
                            append(ch)
                        }
                }
            }
        }

    private fun formatDouble(value: Double): String =
        if (value == value.toLong().toDouble()) {
            "${value.toLong()}.0"
        } else {
            value.toString()
        }

    private const val ESCAPE_CAPACITY_SLACK: Int = 8
    private const val FIRST_PRINTABLE_ASCII: Int = 0x20
    private const val HEX_RADIX: Int = 16
    private const val UNICODE_ESCAPE_WIDTH: Int = 4
}

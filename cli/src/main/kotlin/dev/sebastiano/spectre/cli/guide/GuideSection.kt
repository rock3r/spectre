package dev.sebastiano.spectre.cli.guide

/**
 * One searchable part of a bundled guide topic: the prose under a heading, one bold-lead bullet of
 * that prose, or one row of a table (with the table header, so the row keeps its column names).
 */
internal data class GuideSection(
    val topic: String,
    /** Heading path inside the topic, without the page title. Rows and bullets add their lead. */
    val path: List<String>,
    val text: String,
    val aliases: List<String>,
    /** Sections that share text share a group: a prose section and the bullets cut from it. */
    val group: Int,
    /** The nearest heading, weighted highest by search. */
    val heading: String,
    /** A table row's first cell or a bullet's bold lead, if any. */
    val label: String = "",
) {
    val breadcrumb: String
        get() = (listOf(topic) + path).joinToString(BREADCRUMB_SEPARATOR)
}

internal const val BREADCRUMB_SEPARATOR: String = " › "

// `<!-- aliases: hang, stuck -->` adds search words that the text does not use. Neither
// `spectre guide <topic>` nor search results print them.
private val ALIAS_COMMENT = Regex("""[ \t]*<!--\s*aliases:\s*(.*?)\s*-->""")

/**
 * Removes alias comments, and the lines that hold nothing else. A removed line between two blank
 * lines takes one of them with it, so the page keeps single blank lines between paragraphs.
 */
internal fun stripAliases(markdown: String): String {
    if (!markdown.contains("<!--")) return markdown
    val kept = mutableListOf<String>()
    var droppedAfterBlank = false
    for (line in markdown.split("\n")) {
        val stripped = ALIAS_COMMENT.replace(line, "")
        when {
            stripped != line && stripped.isBlank() ->
                droppedAfterBlank = kept.lastOrNull()?.isBlank() == true
            droppedAfterBlank && line.isBlank() -> droppedAfterBlank = false
            else -> {
                droppedAfterBlank = false
                kept += stripped
            }
        }
    }
    return kept.joinToString("\n")
}

internal fun aliasesIn(text: String): List<String> =
    ALIAS_COMMENT.findAll(text)
        .flatMap { match -> match.groupValues[1].split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

/**
 * Cuts a guide topic into sections at its headings. Lines in fenced code blocks are never headings.
 * The text before the first second-level heading is a section under the page title.
 */
internal fun splitGuide(topic: String, markdown: String): List<GuideSection> =
    GuideSplitter(topic).split(markdown)

private class GuideSplitter(private val topic: String) {
    private val sections = mutableListOf<GuideSection>()
    private val path = mutableListOf<String>()
    private val levels = mutableListOf<Int>()
    private val body = mutableListOf<String>()
    private var headingLine = ""
    private var nextGroup = 0

    fun split(markdown: String): List<GuideSection> {
        var fence: String? = null
        for (line in markdown.replace("\r\n", "\n").split("\n")) {
            val trimmed = line.trim()
            val openingFence = if (fence == null) fenceMarker(trimmed) else null
            val heading = if (fence == null && openingFence == null) parseHeading(line) else null
            when {
                fence != null -> {
                    if (closesFence(trimmed, fence)) fence = null
                    body += line
                }
                openingFence != null -> {
                    fence = openingFence
                    body += line
                }
                heading != null -> {
                    flush()
                    enter(heading, line)
                }
                else -> body += line
            }
        }
        flush()
        return sections
    }

    private fun enter(heading: Pair<Int, String>, line: String) {
        val (level, title) = heading
        while (levels.isNotEmpty() && levels.last() >= level) {
            levels.removeAt(levels.lastIndex)
            path.removeAt(path.lastIndex)
        }
        levels += level
        path += title
        // The page title is not repeated in the intro section's text.
        headingLine = if (level == 1) "" else line
    }

    private fun flush() {
        val sectionPath = if (path.size > 1) path.drop(1) else path.toList()
        val heading = sectionPath.lastOrNull().orEmpty()
        val prose = mutableListOf<String>()
        if (headingLine.isNotEmpty()) prose += headingLine
        var index = 0
        while (index < body.size) {
            val end = tableEnd(index)
            val table = body.subList(index, end)
            when {
                end == index -> prose += body[index]
                table.size >= MIN_TABLE_LINES && isSeparator(table[1]) ->
                    addTableRows(table, sectionPath, heading)
                else -> prose += table
            }
            index = maxOf(end, index + 1)
        }
        val text = prose.joinToString("\n").trim('\n')
        if (text.removePrefix(headingLine).isNotBlank()) addProse(text, sectionPath, heading)
        body.clear()
    }

    private fun tableEnd(start: Int): Int {
        var end = start
        while (end < body.size && isTableLine(body[end])) end++
        return end
    }

    private fun addTableRows(table: List<String>, sectionPath: List<String>, heading: String) {
        val header = table[0] + "\n" + table[1]
        for (row in table.drop(2)) {
            val label = rowLabel(row)
            sections +=
                GuideSection(
                    topic = topic,
                    path = sectionPath + label,
                    text = ALIAS_COMMENT.replace(header + "\n" + row, ""),
                    aliases = aliasesIn(row),
                    group = nextGroup++,
                    heading = heading,
                    label = label,
                )
        }
    }

    private fun addProse(text: String, sectionPath: List<String>, heading: String) {
        val group = nextGroup++
        sections +=
            GuideSection(
                topic = topic,
                path = sectionPath,
                text = stripAliases(text).trim('\n'),
                aliases = aliasesIn(text),
                group = group,
                heading = heading,
            )
        for ((lead, bulletText) in bullets(text)) {
            val headed = if (headingLine.isEmpty()) bulletText else "$headingLine\n\n$bulletText"
            sections +=
                GuideSection(
                    topic = topic,
                    path = sectionPath + lead,
                    text = stripAliases(headed),
                    aliases = aliasesIn(bulletText),
                    group = group,
                    heading = heading,
                    label = lead,
                )
        }
    }
}

/**
 * The top-level bullets with a bold lead (`- **Lead** text`), each with its continuation lines up
 * to the next bullet or blank line.
 */
private fun bullets(text: String): List<Pair<String, String>> {
    val lines = text.split("\n")
    val result = mutableListOf<Pair<String, String>>()
    for ((start, line) in lines.withIndex()) {
        if (!line.startsWith(BULLET_LEAD)) continue
        var end = start + 1
        while (end < lines.size && lines[end].isNotBlank() && !lines[end].startsWith("- ")) end++
        val lead =
            line.removePrefix(BULLET_LEAD).substringBefore("**").trim().removeSuffix(".").trim()
        if (lead.isNotEmpty()) {
            result += cleanLabel(lead) to lines.subList(start, end).joinToString("\n")
        }
    }
    return result
}

private fun fenceMarker(trimmed: String): String? =
    listOf("```", "~~~")
        .firstOrNull { trimmed.startsWith(it) }
        ?.let { marker ->
            marker[0].toString().repeat(trimmed.length - trimmed.trimStart(marker[0]).length)
        }

private fun closesFence(trimmed: String, fence: String): Boolean =
    trimmed.startsWith(fence) && trimmed.trimStart(fence[0]).isEmpty()

private fun parseHeading(line: String): Pair<Int, String>? {
    val level = line.length - line.trimStart('#').length
    val isHeading = level in 1..MAX_HEADING_LEVEL && line.length > level && line[level] == ' '
    if (!isHeading) return null
    // MkDocs attribute lists such as `{#coordinator-unreachable}` are not part of the title.
    val title = line.substring(level).replace(ATTRIBUTE_LIST, "").trim()
    return level to cleanLabel(title)
}

private fun isTableLine(line: String): Boolean = line.trim().startsWith("|")

private fun isSeparator(line: String): Boolean = line.trim().trim('|', '-', ':', ' ').isEmpty()

/** A row's first cell without Markdown, shortened to a readable length. */
private fun rowLabel(row: String): String {
    val firstCell = row.trim().trim('|').split("|").first()
    val label = cleanLabel(ALIAS_COMMENT.replace(firstCell, "")).removeSuffix(".")
    return if (label.length > MAX_LABEL_LENGTH) {
        label.take(MAX_LABEL_LENGTH - 1).trim() + "…"
    } else {
        label
    }
}

private fun cleanLabel(text: String): String = text.replace("`", "").replace("**", "").trim()

private val ATTRIBUTE_LIST = Regex("""\s*\{#[^}]*}\s*$""")
private const val BULLET_LEAD = "- **"
private const val MIN_TABLE_LINES = 3
private const val MAX_HEADING_LEVEL = 6
private const val MAX_LABEL_LENGTH = 70

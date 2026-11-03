package dev.sebastiano.spectre.cli.guide

/**
 * Splits text into lower-case search terms. Splits camelCase and dotted or hyphenated names and
 * also keeps the joined form, so "typeText" gives "type", "text", and "typetext". Drops stop words
 * and applies a small suffix stemmer, so "hangs" finds "hang".
 */
internal fun searchTokens(text: String): List<String> {
    val tokens = mutableListOf<String>()
    for (word in WORD.findAll(text)) {
        val parts = splitCamel(word.value)
        parts
            .map { it.lowercase() }
            .filter { it.length > 1 && it !in STOP_WORDS }
            .forEach { tokens += stem(it) }
        if (parts.size > 1) tokens += word.value.lowercase()
    }
    return tokens
}

/** The stemmed synonyms of a stemmed [term], from [SYNONYM_GROUPS]. */
internal fun synonymsOf(term: String): Set<String> = SYNONYMS[term].orEmpty()

private fun splitCamel(word: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    for (index in 1 until word.length) {
        val current = word[index]
        val previous = word[index - 1]
        val nextIsLower = index + 1 < word.length && word[index + 1].isLowerCase()
        val boundary =
            current.isUpperCase() &&
                (previous.isLowerCase() ||
                    previous.isDigit() ||
                    (previous.isUpperCase() && nextIsLower))
        if (boundary) {
            parts += word.substring(start, index)
            start = index
        }
    }
    parts += word.substring(start)
    return parts
}

private fun stem(word: String): String =
    when {
        word.length > STEM_MIN_IES && word.endsWith("ies") -> word.removeSuffix("ies") + "y"
        word.length > STEM_MIN_ING && word.endsWith("ing") -> word.removeSuffix("ing")
        word.length > STEM_MIN_ES && word.endsWith("shes") -> word.removeSuffix("es")
        word.length > STEM_MIN_ES && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
        else -> word
    }

/**
 * Words that people use for the same problem. The guides cannot use every one, so a query word also
 * matches its group at half weight. This is the offline stand-in for semantic embeddings: it needs
 * no model download and keeps the CLI dependency-free.
 */
private val SYNONYM_GROUPS: List<List<String>> =
    listOf(
        listOf("trap", "gotcha", "pitfall", "caveat", "mistake"),
        listOf("hang", "stuck", "freeze", "block", "hung", "deadlock"),
        listOf("screenshot", "capture", "png", "image", "still"),
        listOf("type", "typing", "keyboard", "keystroke", "key"),
        listOf("click", "tap", "press"),
        listOf("focus", "foreground", "active"),
        listOf("attach", "inject", "agent", "connect"),
        listOf("wait", "idle", "settle", "synchronization"),
        listOf("record", "recording", "video", "mp4"),
        listOf("error", "fail", "failure", "exception", "refuse"),
        listOf("headless", "xvfb", "ci"),
        listOf("teardown", "cleanup", "dispose", "close"),
        listOf("offscreen", "viewport", "clip", "scroll"),
        listOf("node", "element", "widget", "component"),
        listOf("find", "query", "search", "locate", "selector"),
        listOf("tag", "testtag"),
        listOf("jvm", "process", "pid", "app"),
        listOf("permission", "tcc", "consent"),
        listOf("slow", "startup", "aot"),
    )

private val SYNONYMS: Map<String, Set<String>> = buildMap {
    for (group in SYNONYM_GROUPS) {
        val stems = group.map(::stem).toSet()
        for (term in stems) put(term, getOrDefault(term, emptySet()) + (stems - term))
    }
}

private val WORD = Regex("[\\p{L}\\p{N}]+")

private val STOP_WORDS: Set<String> =
    setOf(
        "the",
        "an",
        "to",
        "of",
        "and",
        "or",
        "in",
        "on",
        "for",
        "is",
        "it",
        "this",
        "that",
        "with",
        "how",
        "do",
        "does",
        "can",
        "be",
        "you",
        "your",
        "as",
        "by",
        "at",
        "from",
        "are",
        "will",
        "use",
        "what",
        "which",
        "if",
        "into",
        "my",
        "why",
        "when",
        "after",
        "get",
    )

private const val STEM_MIN_IES = 4
// Keep at least four letters, so "typing" does not become "typ".
private const val STEM_MIN_ING = 6
private const val STEM_MIN_ES = 3

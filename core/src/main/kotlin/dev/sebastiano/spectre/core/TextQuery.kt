package dev.sebastiano.spectre.core

/**
 * Predicate used by [ComposeAutomator.findByText] and the `findByText` family on [AutomatorWindow]
 * to match against a node's text properties (`Text`, `EditableText`).
 *
 * Build via the factory methods on the companion: [exact] for case-sensitive (or case-insensitive
 * via the flag) full-string equality, [substring] for `contains`-style partial matching. Regex /
 * pattern matching is not supported.
 */
data class TextQuery(
    val value: String,
    val matchType: TextMatchType = TextMatchType.Exact,
    val ignoreCase: Boolean = false,
) {

    fun matches(candidate: String?): Boolean {
        if (candidate == null) return false
        return when (matchType) {
            TextMatchType.Exact -> candidate.equals(value, ignoreCase = ignoreCase)
            TextMatchType.Substring -> candidate.contains(value, ignoreCase = ignoreCase)
        }
    }

    companion object {

        fun exact(value: String, ignoreCase: Boolean = false): TextQuery =
            TextQuery(value = value, matchType = TextMatchType.Exact, ignoreCase = ignoreCase)

        fun substring(value: String, ignoreCase: Boolean = false): TextQuery =
            TextQuery(value = value, matchType = TextMatchType.Substring, ignoreCase = ignoreCase)
    }
}

/** How [TextQuery] compares its [TextQuery.value] against a candidate string. */
enum class TextMatchType {
    /** The candidate must equal `value` (subject to `ignoreCase`). */
    Exact,
    /** The candidate must contain `value` as a substring (subject to `ignoreCase`). */
    Substring,
}

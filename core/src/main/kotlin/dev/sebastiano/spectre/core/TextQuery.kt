package dev.sebastiano.spectre.core

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

enum class TextMatchType {
    Exact,
    Substring,
}

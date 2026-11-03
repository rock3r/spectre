package dev.sebastiano.spectre.cli.guide

import kotlin.math.ln

/** A ranked guide section. [text] can be shorter than the section's text after [capHits]. */
internal data class GuideHit(
    val section: GuideSection,
    val score: Double,
    val text: String = section.text,
    val truncated: Boolean = false,
)

/**
 * Ranks guide sections with BM25 over weighted fields. The heading counts most, then the topic, the
 * row or bullet lead, and the alias comments, then the text. Query words also match a small offline
 * synonym table at reduced weight, so "gotcha" finds "trap" and "stuck" finds "hang".
 */
internal class GuideIndex(private val sections: List<GuideSection>) {
    private val documents = sections.map(::IndexedSection)
    private val documentFrequency: Map<String, Int> =
        documents.flatMap { it.termFrequency.keys }.groupingBy { it }.eachCount()
    private val averageLength: Double =
        if (documents.isEmpty()) 0.0 else documents.sumOf { it.length } / documents.size

    /** Returns the best [limit] sections for [query], best first, one per section group. */
    fun search(query: String, limit: Int): List<GuideHit> {
        val terms = searchTokens(query).distinct()
        if (terms.isEmpty()) return emptyList()
        val normalizedQuery = normalizeTitle(query)
        val scored = documents.mapIndexedNotNull { index, document ->
            score(document, terms, normalizedQuery)?.let { GuideHit(sections[index], it) }
        }
        val topScore = scored.maxOfOrNull { it.score } ?: return emptyList()
        return scored
            .map { hit ->
                hit.copy(score = hit.score + topicIntentBoost(hit, normalizedQuery, topScore))
            }
            .sortedByDescending { it.score }
            // One hit per group: a section's bullets would otherwise crowd out other topics.
            .distinctBy { it.section.topic to it.section.group }
            .take(limit)
            .map { it.copy(score = (it.score * SCORE_PRECISION).toInt() / SCORE_PRECISION) }
    }

    private fun score(
        document: IndexedSection,
        terms: List<String>,
        normalizedQuery: String,
    ): Double? {
        var score = 0.0
        var matched = 0
        for (term in terms) {
            val direct = bm25(document, term)
            val viaSynonym =
                synonymsOf(term).maxOfOrNull { bm25(document, it) * SYNONYM_WEIGHT } ?: 0.0
            val contribution = maxOf(direct, viaSynonym)
            if (contribution > 0.0) {
                score += contribution
                matched++
            }
        }
        if (score == 0.0) return null
        // Prefer sections that match every query word, and exact heading matches.
        score *= HALF + HALF * matched / terms.size
        if (document.normalizedHeading == normalizedQuery) score *= EXACT_HEADING_BOOST
        return score
    }

    private fun bm25(document: IndexedSection, term: String): Double {
        val frequency = document.termFrequency[term] ?: return 0.0
        val total = documents.size.toDouble()
        val containing = documentFrequency[term] ?: 0
        val idf = ln(1 + (total - containing + HALF) / (containing + HALF))
        val lengthNorm = 1 - B + B * document.length / averageLength
        return idf * frequency * (K1 + 1) / (frequency + K1 * lengthNorm)
    }

    /** A query that starts with a topic name, such as "cli capture", prefers that topic. */
    private fun topicIntentBoost(hit: GuideHit, normalizedQuery: String, topScore: Double): Double {
        val topic = normalizeTitle(hit.section.topic)
        val prefersTopic = normalizedQuery == topic || normalizedQuery.startsWith("$topic ")
        return if (prefersTopic) topScore + 1 else 0.0
    }
}

private class IndexedSection(section: GuideSection) {
    val termFrequency: Map<String, Double>
    val length: Double
    val normalizedHeading: String = normalizeTitle(section.heading)

    init {
        val frequency = mutableMapOf<String, Double>()
        fun add(text: String, weight: Double) {
            for (token in searchTokens(text)) frequency.merge(token, weight, Double::plus)
        }
        add(section.heading, HEADING_WEIGHT)
        add(
            section.topic + " " + section.label + " " + section.aliases.joinToString(" "),
            HEAD_WEIGHT,
        )
        add(section.text, BODY_WEIGHT)
        termFrequency = frequency
        length = frequency.values.sum()
    }
}

/** Removes Markdown punctuation and case so "`cli`" and "CLI" compare equal. */
private fun normalizeTitle(text: String): String =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")

private const val HEADING_WEIGHT = 4.0
private const val HEAD_WEIGHT = 2.0
private const val BODY_WEIGHT = 1.0
private const val K1 = 1.2
private const val B = 0.75
private const val HALF = 0.5
private const val SYNONYM_WEIGHT = 0.5
private const val EXACT_HEADING_BOOST = 3.0
private const val SCORE_PRECISION = 100.0

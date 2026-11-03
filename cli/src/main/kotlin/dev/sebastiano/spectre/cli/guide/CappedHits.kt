package dev.sebastiano.spectre.cli.guide

/** Search hits that fit the byte budget, and how many hits did not fit. */
internal data class CappedHits(val hits: List<GuideHit>, val omitted: Int)

/**
 * Limits each hit's text to [perHitBytes] and all texts together to [totalBytes] of UTF-8, so a
 * search never prints as much as the pages it replaces. Text is cut at a line end where possible,
 * and a cut hit says which topic holds the rest. Hits that no longer fit are dropped and counted.
 */
internal fun capHits(hits: List<GuideHit>, perHitBytes: Int, totalBytes: Int): CappedHits {
    val kept = mutableListOf<GuideHit>()
    var left = totalBytes
    for ((index, hit) in hits.withIndex()) {
        val limit = minOf(perHitBytes, left)
        if (kept.isNotEmpty() && limit < MIN_USEFUL_HIT_BYTES) {
            return CappedHits(kept, hits.size - index)
        }
        val capped =
            if (hit.text.utf8Size() <= limit) hit
            else {
                val note = "\n… (the rest is in `spectre guide ${hit.section.topic}`)"
                val room = (limit - note.utf8Size()).coerceAtLeast(0)
                hit.copy(text = cutAtLine(hit.text, room) + note, truncated = true)
            }
        left -= capped.text.utf8Size()
        kept += capped
    }
    return CappedHits(kept, 0)
}

/**
 * Shortens [text] to at most [maxBytes] of UTF-8: at the last line end in the second half if there
 * is one, otherwise at the last whole character.
 */
private fun cutAtLine(text: String, maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        val size = String(Character.toChars(codePoint)).utf8Size()
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    val prefix = text.substring(0, end)
    val lineEnd = prefix.lastIndexOf('\n')
    return if (lineEnd > prefix.length / 2) prefix.substring(0, lineEnd) else prefix
}

internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private const val MIN_USEFUL_HIT_BYTES = 200

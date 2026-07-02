package com.mangaread.core.metadata

import com.mangaread.core.domain.normalizeSortTitle

/**
 * Picks the best auto-match candidate for a scanned folder title (PLAN.md §9/§9.2). The
 * fuzzy matcher is allowed to be imperfect (Fix Metadata exists to correct it, §9.1) but
 * a wrong match is worse than no match — [threshold] keeps weak candidates from binding
 * at all, leaving the series to show file-derived data until a user fixes it or a better
 * candidate search wins on a later pass.
 */
fun bestMatch(title: String, candidates: List<RemoteWork>, threshold: Double = 0.5): RemoteWork? {
    val normalizedTitle = normalizeSortTitle(title)
    return candidates
        .map { it to titleSimilarity(normalizedTitle, normalizeSortTitle(it.title)) }
        .filter { (_, score) -> score >= threshold }
        .maxByOrNull { (_, score) -> score }
        ?.first
}

/** 1.0 = identical (post-normalization), 0.0 = completely different; Levenshtein-ratio based. */
internal fun titleSimilarity(normalizedA: String, normalizedB: String): Double {
    if (normalizedA == normalizedB) return 1.0
    val maxLen = maxOf(normalizedA.length, normalizedB.length)
    if (maxLen == 0) return 1.0
    return 1.0 - levenshtein(normalizedA, normalizedB).toDouble() / maxLen
}

private fun levenshtein(a: String, b: String): Int {
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var previousRow = IntArray(b.length + 1) { it }
    var currentRow = IntArray(b.length + 1)
    for (i in 1..a.length) {
        currentRow[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            currentRow[j] = minOf(
                previousRow[j] + 1,        // deletion
                currentRow[j - 1] + 1,     // insertion
                previousRow[j - 1] + cost, // substitution
            )
        }
        val tmp = previousRow
        previousRow = currentRow
        currentRow = tmp
    }
    return previousRow[b.length]
}

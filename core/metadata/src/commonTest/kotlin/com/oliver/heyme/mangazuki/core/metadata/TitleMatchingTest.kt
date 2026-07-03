package com.oliver.heyme.mangazuki.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TitleMatchingTest {

    private fun work(id: String, title: String) = RemoteWork(externalId = id, title = title, coverUrl = null, startYear = null)

    @Test
    fun exact_match_after_normalization_wins() {
        val candidates = listOf(work("1", "Berserk"), work("2", "Vagabond"))
        val match = bestMatch("berserk!!", candidates)
        assertEquals("1", match?.externalId)
    }

    @Test
    fun close_fuzzy_match_above_threshold_wins() {
        val candidates = listOf(work("1", "One Piece"), work("2", "Vagabond"))
        // Missing a letter — still close enough to the real title.
        val match = bestMatch("One Piec", candidates)
        assertEquals("1", match?.externalId)
    }

    @Test
    fun no_close_candidate_returns_null() {
        val candidates = listOf(work("1", "Zzzzzzzzzzzzzzzz"))
        assertNull(bestMatch("Qwqwqwqwqwqwqwqw", candidates))
    }

    @Test
    fun empty_candidates_returns_null() {
        assertNull(bestMatch("Anything", emptyList()))
    }
}

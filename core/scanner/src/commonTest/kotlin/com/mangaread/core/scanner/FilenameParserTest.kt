package com.mangaread.core.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filename parser corpus — the Phase 1 gate (PLAN.md §14).
 *
 * These are SYNTHETIC cases covering common scanlation/library conventions
 * (Mihon/Komga/Kavita-style). **Add your REAL filenames here** before trusting the
 * parser; when a real name parses wrong, add it as a failing case first, then fix
 * the regex. Known gaps are marked.
 */
class FilenameParserTest {

    private data class Case(val raw: String, val volume: Double?, val number: Double?)

    private val cases = listOf(
        Case("Vol.01 Ch.001.5", 1.0, 1.5),
        Case("[Group] Title v2 c12 (1080p)", 2.0, 12.0),
        Case("Chapter 12", null, 12.0),
        Case("Chapter12", null, 12.0),
        Case("Berserk v01 c003", 1.0, 3.0),
        Case("Solo Leveling Vol. 2 Chapter 12.5", 2.0, 12.5),
        Case("v05.cbz", 5.0, null),
        Case("Vol01", 1.0, null),
        Case("Ch. 7 - The Beginning", null, 7.0),
        Case("Episode 5", null, 5.0),
        Case("#42", null, 42.0),
        // Japanese title with a latin chapter token — title parsing is best-effort,
        // volume marker (巻) is a known gap (volume stays null) until the corpus grows.
        Case("進撃の巨人 c001", null, 1.0),
    )

    @Test
    fun parses_volume_and_chapter_numbers() {
        for (c in cases) {
            val p = FilenameParser.parse(c.raw)
            assertEquals(c.volume, p.volume, "volume mismatch for '${c.raw}'")
            assertEquals(c.number, p.number, "number mismatch for '${c.raw}'")
        }
    }

    @Test
    fun trailing_bare_number_is_a_fallback_chapter() {
        val p = FilenameParser.parse("One Piece - 1095")
        assertEquals(1095.0, p.number)
        assertTrue(p.numberIsFallback, "no ch/vol keyword → number came from fallback")

        val k = FilenameParser.parse("Chapter 12")
        assertTrue(!k.numberIsFallback, "explicit keyword is NOT a fallback")
    }

    @Test
    fun fallback_chapter_from_bare_number_with_extension() {
        val p = FilenameParser.parse("Title 012.cbz")
        assertEquals(null, p.volume)
        assertEquals(12.0, p.number)
        assertTrue(p.numberIsFallback)
    }

    @Test
    fun derives_clean_series_title_on_unambiguous_names() {
        assertEquals("Title", FilenameParser.parse("[Group] Title v2 c12 (1080p)").seriesTitle)
        assertEquals("Berserk", FilenameParser.parse("Berserk v01 c003").seriesTitle)
        assertEquals("Solo Leveling", FilenameParser.parse("Solo Leveling Vol. 2 Chapter 12.5").seriesTitle)
        assertEquals("The Beginning", FilenameParser.parse("Ch. 7 - The Beginning").seriesTitle)
    }

    @Test
    fun title_word_starting_with_c_is_not_a_chapter() {
        // Regression: "\bc" must require an immediately-following digit.
        val p = FilenameParser.parse("Crystal Saga Chapter 3")
        assertEquals(3.0, p.number, "real chapter still parses")
        assertEquals("Crystal Saga", p.seriesTitle, "'Crystal' not mistaken for a chapter token")
    }
}

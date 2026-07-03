package com.oliver.heyme.mangazuki.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `normalizeSortTitle` is the FROZEN sync fallback key (PLAN.md §10). A silent change here
 * breaks cross-device read-status matching for unmatched series — pin its behavior.
 */
class SortTitleTest {

    @Test
    fun lowercases_strips_punctuation_collapses_whitespace() {
        // Real example from a scanned library.
        assertEquals("2dk g pen alarm clock", normalizeSortTitle("2DK, G-pen, Alarm clock"))
        assertEquals("the manga", normalizeSortTitle("The Manga!"))
        assertEquals("a b c", normalizeSortTitle("  a   b\tc  "))
    }

    @Test
    fun nfc_folds_equivalent_unicode() {
        // Built from code points so the two forms are provably different regardless of file encoding.
        val eAcute = 0x00E9.toChar()        // precomposed 'é'
        val combiningAcute = 0x0301.toChar() // combining accent
        val composed = "Caf" + eAcute
        val decomposed = "Cafe" + combiningAcute
        assertEquals(normalizeSortTitle(composed), normalizeSortTitle(decomposed))
        assertEquals("caf" + eAcute, normalizeSortTitle(decomposed))
    }

    @Test
    fun digits_and_letters_are_preserved() {
        assertEquals("chapter 12 5", normalizeSortTitle("Chapter 12.5"))
    }

    @Test
    fun empty_after_stripping_is_empty() {
        assertEquals("", normalizeSortTitle("!!! ---"))
    }
}

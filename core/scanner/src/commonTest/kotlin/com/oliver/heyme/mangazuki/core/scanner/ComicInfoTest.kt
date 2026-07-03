package com.oliver.heyme.mangazuki.core.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicInfoTest {

    @Test
    fun extracts_series_element_text() {
        val xml = "<?xml version=\"1.0\"?><ComicInfo><Series>Attack on Titan</Series><Number>1</Number></ComicInfo>"
        assertEquals("Attack on Titan", parseComicInfoSeriesTitle(xml))
    }

    @Test
    fun unescapes_xml_entities() {
        val xml = "<ComicInfo><Series>Fullmetal Alchemist &amp; Friends</Series></ComicInfo>"
        assertEquals("Fullmetal Alchemist & Friends", parseComicInfoSeriesTitle(xml))
    }

    @Test
    fun trims_surrounding_whitespace() {
        val xml = "<ComicInfo><Series>  Berserk  </Series></ComicInfo>"
        assertEquals("Berserk", parseComicInfoSeriesTitle(xml))
    }

    @Test
    fun missing_series_element_returns_null() {
        val xml = "<ComicInfo><Title>Chapter 1</Title></ComicInfo>"
        assertNull(parseComicInfoSeriesTitle(xml))
    }

    @Test
    fun blank_series_element_returns_null() {
        val xml = "<ComicInfo><Series>   </Series></ComicInfo>"
        assertNull(parseComicInfoSeriesTitle(xml))
    }
}

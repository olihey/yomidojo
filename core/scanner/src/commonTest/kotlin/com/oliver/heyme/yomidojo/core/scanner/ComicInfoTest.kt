package com.oliver.heyme.yomidojo.core.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComicInfoTest {

    @Test
    fun extracts_series_element_text() {
        val xml = "<?xml version=\"1.0\"?><ComicInfo><Series>Attack on Titan</Series><Number>1</Number></ComicInfo>"
        assertEquals("Attack on Titan", parseComicInfoMeta(xml).seriesTitle)
    }

    @Test
    fun extracts_title_element_text() {
        val xml = "<ComicInfo><Series>Attack on Titan</Series><Title>The Fall of Shiganshina</Title></ComicInfo>"
        assertEquals("The Fall of Shiganshina", parseComicInfoMeta(xml).title)
    }

    @Test
    fun unescapes_xml_entities() {
        val xml = "<ComicInfo><Series>Fullmetal Alchemist &amp; Friends</Series></ComicInfo>"
        assertEquals("Fullmetal Alchemist & Friends", parseComicInfoMeta(xml).seriesTitle)
    }

    @Test
    fun trims_surrounding_whitespace() {
        val xml = "<ComicInfo><Series>  Berserk  </Series></ComicInfo>"
        assertEquals("Berserk", parseComicInfoMeta(xml).seriesTitle)
    }

    @Test
    fun missing_series_element_returns_null_series_but_still_reads_title() {
        val xml = "<ComicInfo><Title>Chapter 1</Title></ComicInfo>"
        val meta = parseComicInfoMeta(xml)
        assertNull(meta.seriesTitle)
        assertEquals("Chapter 1", meta.title)
    }

    @Test
    fun blank_series_element_returns_null() {
        val xml = "<ComicInfo><Series>   </Series></ComicInfo>"
        assertNull(parseComicInfoMeta(xml).seriesTitle)
    }

    @Test
    fun missing_title_element_returns_null_title_but_still_reads_series() {
        val xml = "<ComicInfo><Series>Berserk</Series></ComicInfo>"
        val meta = parseComicInfoMeta(xml)
        assertEquals("Berserk", meta.seriesTitle)
        assertNull(meta.title)
    }
}

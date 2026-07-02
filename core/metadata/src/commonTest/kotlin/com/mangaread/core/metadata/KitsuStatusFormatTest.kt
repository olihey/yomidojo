package com.mangaread.core.metadata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KitsuStatusFormatTest {

    @Test
    fun status_maps_to_anilist_canonical_values() {
        assertEquals("FINISHED", normalizeStatus("finished"))
        assertEquals("RELEASING", normalizeStatus("current"))
        assertEquals("NOT_YET_RELEASED", normalizeStatus("tba"))
        assertEquals("NOT_YET_RELEASED", normalizeStatus("unreleased"))
        assertEquals("NOT_YET_RELEASED", normalizeStatus("upcoming"))
    }

    @Test
    fun unknown_status_returns_null() {
        assertNull(normalizeStatus("something_new"))
        assertNull(normalizeStatus(null))
    }

    @Test
    fun subtype_maps_manhwa_and_manhua_to_manga() {
        assertEquals("MANGA", normalizeFormat("manga"))
        assertEquals("MANGA", normalizeFormat("manhwa"))
        assertEquals("MANGA", normalizeFormat("manhua"))
        assertEquals("NOVEL", normalizeFormat("novel"))
    }

    @Test
    fun unknown_subtype_returns_null() {
        assertNull(normalizeFormat("something_new"))
        assertNull(normalizeFormat(null))
    }

    /** Regression: Kitsu's `titles` object can carry an explicit JSON `null` for a locale key
     * (e.g. `en`) alongside a real value in another (e.g. `en_jp`) — preferredTitle must skip
     * the null rather than surface it, since `titles["en"]` returning null is indistinguishable
     * from the key being absent. */
    @Test
    fun preferred_title_skips_null_locale_values() {
        val attrs = KitsuAttributes(titles = mapOf("en" to null, "en_jp" to "Yagate Kimi ni Naru"))
        assertEquals("Yagate Kimi ni Naru", attrs.preferredTitle())
    }

    @Test
    fun preferred_title_prefers_canonical_then_en_us_then_en() {
        assertEquals("Canon", KitsuAttributes(canonicalTitle = "Canon", titles = mapOf("en" to "English")).preferredTitle())
        assertEquals("US", KitsuAttributes(titles = mapOf("en_us" to "US", "en" to "English")).preferredTitle())
        assertEquals("Unknown", KitsuAttributes(titles = mapOf("en" to null)).preferredTitle())
    }
}

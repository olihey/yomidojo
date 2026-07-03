package com.oliver.heyme.mangazuki.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdsTest {

    @Test
    fun id_is_32_hex_chars() {
        val id = deterministicId("local", "/manga/Berserk/Vol01.cbz")
        assertEquals(32, id.length, "first 16 bytes => 32 hex chars")
        assertTrue(id.all { it in "0123456789abcdef" }, "lowercase hex only")
    }

    @Test
    fun id_is_stable_across_calls() {
        val a = deterministicId("local", "/manga/Berserk/Vol01.cbz")
        val b = deterministicId("local", "/manga/Berserk/Vol01.cbz")
        assertEquals(a, b, "same inputs must always yield the same id (frozen)")
    }

    @Test
    fun frozen_value_must_not_drift() {
        // GOLDEN VALUE — SHA-256("local /manga/Berserk/Vol01.cbz")[0..15] in hex.
        // If this assertion ever fails, the hash definition changed and every
        // persisted id is invalidated. Do NOT update it casually (PLAN.md §5).
        assertEquals(
            "fe81c7cfc0d1ccb36d99a7e1fdcdede2",
            deterministicId("local", "/manga/Berserk/Vol01.cbz"),
            "golden id mismatch — the frozen hash definition was changed",
        )
    }

    @Test
    fun separator_and_whitespace_are_normalized() {
        val unix = deterministicId("local", "/manga/Berserk/Vol01.cbz")
        val win = deterministicId("local", "\\manga\\Berserk\\Vol01.cbz")
        val padded = deterministicId("local", "  /manga/Berserk/Vol01.cbz  ")
        assertEquals(unix, win, "backslashes unify to '/'")
        assertEquals(unix, padded, "surrounding whitespace trimmed")
    }

    @Test
    fun nfc_makes_equivalent_unicode_equal() {
        // "é" composed (U+00E9) vs decomposed (e + U+0301) must hash identically after NFC.
        val composed = deterministicId("local", "/manga/Café/ch1")
        val decomposed = deterministicId("local", "/manga/Café/ch1")
        assertEquals(composed, decomposed, "NFC must fold equivalent unicode")
    }

    @Test
    fun different_source_or_locator_differ() {
        val base = deterministicId("local", "/a")
        assertNotEquals(base, deterministicId("onedrive", "/a"), "source_id participates")
        assertNotEquals(base, deterministicId("local", "/b"), "locator participates")
    }

    @Test
    fun case_is_preserved() {
        // Filesystems differ on case-sensitivity; the hash does not lowercase the locator.
        assertNotEquals(
            deterministicId("local", "/Manga"),
            deterministicId("local", "/manga"),
        )
    }
}

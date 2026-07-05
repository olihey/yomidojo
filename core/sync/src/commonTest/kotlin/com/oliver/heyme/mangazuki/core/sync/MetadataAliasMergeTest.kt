package com.oliver.heyme.mangazuki.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MetadataAliasMergeTest {

    private fun alias(
        title: String = "sirius",
        provider: String = "ANILIST",
        externalId: String = "16498",
        updatedAt: Long = 0,
        deviceId: String = "device-a",
    ) = MetadataAliasRecord(title, provider, externalId, updatedAt, deviceId)

    @Test
    fun winner_is_the_most_recently_updated_alias_for_that_title() {
        val older = alias(updatedAt = 100, externalId = "1")
        val newer = alias(updatedAt = 200, externalId = "2")

        assertEquals(listOf(newer), resolveAliasWinners(listOf(older, newer)))
    }

    @Test
    fun winner_tiebreaks_deterministically_on_equal_updatedAt() {
        val fromDeviceA = alias(updatedAt = 100, deviceId = "aaaa", externalId = "1")
        val fromDeviceB = alias(updatedAt = 100, deviceId = "zzzz", externalId = "2")

        assertEquals(listOf(fromDeviceB), resolveAliasWinners(listOf(fromDeviceA, fromDeviceB)))
        assertEquals(listOf(fromDeviceB), resolveAliasWinners(listOf(fromDeviceB, fromDeviceA)))
    }

    @Test
    fun distinct_titles_each_keep_their_own_winner() {
        val sirius = alias(title = "sirius")
        val vagabond = alias(title = "vagabond", externalId = "999")

        val winners = resolveAliasWinners(listOf(sirius, vagabond))

        assertEquals(setOf(sirius, vagabond), winners.toSet())
    }

    @Test
    fun bridging_fills_in_provider_for_an_unmatched_record_with_a_known_alias() {
        val unmatched = SeriesProgressRecord(
            key = SeriesKey(null, null, "sirius"),
            completedVolumes = emptyList(), inProgressVolumes = emptyList(), updatedAt = 0,
        )

        val bridged = unmatched.bridgedWith(listOf(alias(title = "sirius")))

        assertEquals("ANILIST", bridged.key.provider)
        assertEquals("16498", bridged.key.externalId)
        assertEquals("sirius", bridged.key.normalizedTitle, "bridging must never touch the record's own title")
    }

    @Test
    fun bridging_leaves_an_already_matched_record_untouched() {
        val matched = SeriesProgressRecord(
            key = SeriesKey("KITSU", "7", "sirius"),
            completedVolumes = emptyList(), inProgressVolumes = emptyList(), updatedAt = 0,
        )

        val result = matched.bridgedWith(listOf(alias(title = "sirius", provider = "ANILIST", externalId = "16498")))

        assertSame(matched, result, "a genuine match must never be overridden by an alias")
    }

    @Test
    fun bridging_is_a_no_op_when_no_alias_matches_the_title() {
        val unmatched = SeriesProgressRecord(
            key = SeriesKey(null, null, "some other title"),
            completedVolumes = emptyList(), inProgressVolumes = emptyList(), updatedAt = 0,
        )

        val result = unmatched.bridgedWith(listOf(alias(title = "sirius")))

        assertSame(unmatched, result)
    }
}

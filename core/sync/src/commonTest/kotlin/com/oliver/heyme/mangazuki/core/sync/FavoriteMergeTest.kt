package com.oliver.heyme.mangazuki.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteMergeTest {

    private fun favorite(
        title: String = "sirius",
        provider: String? = "ANILIST",
        externalId: String? = "16498",
        favorited: Boolean = true,
        updatedAt: Long = 0,
        deviceId: String = "device-a",
    ) = FavoriteRecord(title, provider, externalId, favorited, updatedAt, deviceId)

    @Test
    fun a_newer_unfavorite_beats_an_older_favorite() {
        // The whole point of the explicit tombstone (PLAN.md §10, progress-v3 lesson): removing
        // a heart on one device must survive a merge against another device's stale heart.
        val hearted = favorite(favorited = true, updatedAt = 100)
        val unhearted = favorite(favorited = false, updatedAt = 200)

        assertEquals(listOf(unhearted), resolveFavoriteWinners(listOf(hearted, unhearted)))
    }

    @Test
    fun a_newer_favorite_beats_an_older_unfavorite() {
        val unhearted = favorite(favorited = false, updatedAt = 100)
        val rehearted = favorite(favorited = true, updatedAt = 200)

        assertEquals(listOf(rehearted), resolveFavoriteWinners(listOf(rehearted, unhearted)))
    }

    @Test
    fun winner_tiebreaks_deterministically_on_equal_updatedAt() {
        val fromDeviceA = favorite(updatedAt = 100, deviceId = "aaaa", favorited = true)
        val fromDeviceB = favorite(updatedAt = 100, deviceId = "zzzz", favorited = false)

        assertEquals(listOf(fromDeviceB), resolveFavoriteWinners(listOf(fromDeviceA, fromDeviceB)))
        assertEquals(listOf(fromDeviceB), resolveFavoriteWinners(listOf(fromDeviceB, fromDeviceA)))
    }

    @Test
    fun distinct_titles_each_keep_their_own_winner() {
        val sirius = favorite(title = "sirius")
        val vagabond = favorite(title = "vagabond", externalId = "999")

        assertEquals(setOf(sirius, vagabond), resolveFavoriteWinners(listOf(sirius, vagabond)).toSet())
    }

    @Test
    fun unmatched_series_group_by_title_alone() {
        // A device that hasn't AniList-matched the series yet still converges with one that has:
        // grouping is by normalizedTitle, so the matched record's newer write wins the group.
        val unmatched = favorite(provider = null, externalId = null, favorited = true, updatedAt = 100)
        val matched = favorite(provider = "ANILIST", externalId = "16498", favorited = false, updatedAt = 200)

        assertEquals(listOf(matched), resolveFavoriteWinners(listOf(unmatched, matched)))
    }
}

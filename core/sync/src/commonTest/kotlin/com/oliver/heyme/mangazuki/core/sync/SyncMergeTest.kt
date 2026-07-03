package com.oliver.heyme.mangazuki.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncMergeTest {

    private fun record(
        provider: String? = null,
        externalId: String? = null,
        title: String = "attack on titan",
        volume: Double? = null,
        number: Double? = 1.0,
        completed: Boolean = false,
        lastPageIndex: Int = 0,
        updatedAt: Long = 0,
        deviceId: String = "device-a",
    ) = ProgressRecord(
        key = ProgressKey(provider, externalId, title, volume, number),
        completed = completed,
        lastPageIndex = lastPageIndex,
        updatedAt = updatedAt,
        deviceId = deviceId,
    )

    @Test
    fun case1_same_provider_equal_ids_merge() {
        val a = record(provider = "ANILIST", externalId = "16498")
        val b = record(provider = "ANILIST", externalId = "16498")

        val groups = resolveSyncGroups(listOf(a, b))

        assertEquals(1, groups.size)
        assertEquals(setOf(a, b), groups.single().toSet())
    }

    @Test
    fun case2_same_provider_different_ids_never_merge_even_with_matching_title() {
        val a = record(provider = "ANILIST", externalId = "16498", title = "sirius")
        val b = record(provider = "ANILIST", externalId = "99999", title = "sirius")

        val groups = resolveSyncGroups(listOf(a, b))

        assertEquals(2, groups.size, "same-provider id disagreement must never be bridged by a title match")
        assertTrue(groups.all { it.size == 1 })
    }

    @Test
    fun case3_different_providers_bridge_via_title() {
        val aniList = record(provider = "ANILIST", externalId = "16498", title = "attack on titan")
        val kitsu = record(provider = "KITSU", externalId = "7", title = "attack on titan")

        val groups = resolveSyncGroups(listOf(aniList, kitsu))

        assertEquals(1, groups.size, "different providers with no shared id space should bridge on title agreement")
        assertEquals(setOf(aniList, kitsu), groups.single().toSet())
    }

    @Test
    fun case3_missing_id_on_one_side_falls_back_to_title() {
        val matched = record(provider = "ANILIST", externalId = "16498", title = "attack on titan")
        val unmatched = record(provider = null, externalId = null, title = "attack on titan")

        val groups = resolveSyncGroups(listOf(matched, unmatched))

        assertEquals(1, groups.size)
        assertEquals(setOf(matched, unmatched), groups.single().toSet())
    }

    @Test
    fun worked_example_title_bucket_with_internal_provider_conflict_never_bridges() {
        // Two AniList entries genuinely disagree (case 2) while sharing a title, plus one
        // untagged record that also matches the title -- there's no principled way to know
        // which of the two conflicting AniList entries the untagged record belongs to, so
        // none of the three should merge.
        val entryA = record(provider = "ANILIST", externalId = "16498", title = "sirius")
        val entryB = record(provider = "ANILIST", externalId = "99999", title = "sirius")
        val untagged = record(provider = null, externalId = null, title = "sirius")

        val groups = resolveSyncGroups(listOf(entryA, entryB, untagged))

        assertEquals(3, groups.size, "an internal same-provider conflict must veto bridging the whole title bucket")
        assertTrue(groups.all { it.size == 1 })
    }

    @Test
    fun distinct_titles_never_group_together() {
        val titanChapter = record(title = "attack on titan", number = 1.0)
        val siriusChapter = record(title = "sirius", number = 1.0)

        val groups = resolveSyncGroups(listOf(titanChapter, siriusChapter))

        assertEquals(2, groups.size)
    }

    @Test
    fun winner_is_the_most_recently_updated_record() {
        val older = record(updatedAt = 100, completed = false)
        val newer = record(updatedAt = 200, completed = true)

        assertEquals(newer, winner(listOf(older, newer)))
    }

    @Test
    fun winner_tiebreaks_deterministically_on_equal_updatedAt() {
        val fromDeviceA = record(updatedAt = 100, deviceId = "aaaa")
        val fromDeviceB = record(updatedAt = 100, deviceId = "zzzz")

        // Deterministic regardless of input order -- both devices computing this merge
        // independently must arrive at the same winner or they'd never converge.
        assertEquals(fromDeviceB, winner(listOf(fromDeviceA, fromDeviceB)))
        assertEquals(fromDeviceB, winner(listOf(fromDeviceB, fromDeviceA)))
    }
}

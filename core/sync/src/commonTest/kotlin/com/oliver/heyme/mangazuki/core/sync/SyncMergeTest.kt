package com.oliver.heyme.mangazuki.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncMergeTest {

    private fun record(
        provider: String? = null,
        externalId: String? = null,
        title: String = "attack on titan",
        completedVolumes: List<VolumeChapterKey> = emptyList(),
        inProgressVolumes: List<InProgressVolume> = emptyList(),
        updatedAt: Long = 0,
    ) = SeriesProgressRecord(
        key = SeriesKey(provider, externalId, title),
        completedVolumes = completedVolumes,
        inProgressVolumes = inProgressVolumes,
        updatedAt = updatedAt,
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
        val titan = record(title = "attack on titan")
        val sirius = record(title = "sirius")

        val groups = resolveSyncGroups(listOf(titan, sirius))

        assertEquals(2, groups.size)
    }

    @Test
    fun winner_unions_completed_volumes_across_records() {
        val local = record(completedVolumes = listOf(VolumeChapterKey(null, 1.0)), updatedAt = 100)
        val remote = record(completedVolumes = listOf(VolumeChapterKey(null, 2.0)), updatedAt = 200)

        val merged = winner(listOf(local, remote))

        assertEquals(
            setOf(VolumeChapterKey(null, 1.0), VolumeChapterKey(null, 2.0)),
            merged.completedVolumes.toSet(),
            "completion is monotonic -- a union never needs a timestamp to arbitrate",
        )
    }

    @Test
    fun winner_keeps_the_newest_inProgressVolumes_list_wholesale() {
        val older = record(inProgressVolumes = listOf(InProgressVolume(null, 5.0, 10)), updatedAt = 100)
        val newer = record(inProgressVolumes = listOf(InProgressVolume(null, 5.0, 50)), updatedAt = 200)

        val merged = winner(listOf(older, newer))

        assertEquals(listOf(InProgressVolume(null, 5.0, 50)), merged.inProgressVolumes)
        assertEquals(200, merged.updatedAt)
    }

    @Test
    fun winner_ties_break_by_whichever_record_is_encountered_first() {
        val first = record(inProgressVolumes = listOf(InProgressVolume(null, 5.0, 10)), updatedAt = 100)
        val second = record(inProgressVolumes = listOf(InProgressVolume(null, 5.0, 99)), updatedAt = 100)

        // Deterministic given a fixed input order -- not meaningful beyond that, since there's
        // no per-device tiebreak field anymore (PLAN.md §10).
        assertEquals(listOf(InProgressVolume(null, 5.0, 10)), winner(listOf(first, second)).inProgressVolumes)
    }

    @Test
    fun winner_drops_an_inProgress_entry_that_another_record_has_since_completed() {
        val stillInProgress = record(inProgressVolumes = listOf(InProgressVolume(null, 5.0, 10)), updatedAt = 100)
        val finishedElsewhere = record(completedVolumes = listOf(VolumeChapterKey(null, 5.0)), updatedAt = 200)

        val merged = winner(listOf(stillInProgress, finishedElsewhere))

        assertEquals(listOf(VolumeChapterKey(null, 5.0)), merged.completedVolumes)
        assertTrue(merged.inProgressVolumes.isEmpty(), "a stale in-progress marker must never resurrect a finished chapter")
    }
}

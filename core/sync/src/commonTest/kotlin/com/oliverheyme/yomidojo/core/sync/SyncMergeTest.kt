package com.oliverheyme.yomidojo.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncMergeTest {

    private fun volume(number: Double, completed: Boolean, updatedAt: Long, lastPageIndex: Int = 0, volume: Double? = null) =
        VolumeProgress(volume, number, completed, lastPageIndex, updatedAt)

    private fun record(
        provider: String? = null,
        externalId: String? = null,
        title: String = "attack on titan",
        volumes: List<VolumeProgress> = emptyList(),
    ) = SeriesProgressRecord(key = SeriesKey(provider, externalId, title), volumes = volumes)

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
    fun winner_keeps_both_completed_chapters_when_they_dont_conflict() {
        val local = record(volumes = listOf(volume(number = 1.0, completed = true, updatedAt = 100)))
        val remote = record(volumes = listOf(volume(number = 2.0, completed = true, updatedAt = 200)))

        val merged = winner(listOf(local, remote))

        assertEquals(
            setOf(volume(number = 1.0, completed = true, updatedAt = 100), volume(number = 2.0, completed = true, updatedAt = 200)),
            merged.volumes.toSet(),
            "different chapters never conflict, so both survive regardless of which side is newer",
        )
    }

    @Test
    fun winner_picks_the_newer_entry_for_the_same_chapter() {
        val older = record(volumes = listOf(volume(number = 5.0, completed = false, updatedAt = 100, lastPageIndex = 10)))
        val newer = record(volumes = listOf(volume(number = 5.0, completed = false, updatedAt = 200, lastPageIndex = 50)))

        val merged = winner(listOf(older, newer))

        assertEquals(listOf(volume(number = 5.0, completed = false, updatedAt = 200, lastPageIndex = 50)), merged.volumes)
    }

    @Test
    fun winner_ties_break_by_whichever_entry_is_encountered_first() {
        val first = record(volumes = listOf(volume(number = 5.0, completed = false, updatedAt = 100, lastPageIndex = 10)))
        val second = record(volumes = listOf(volume(number = 5.0, completed = false, updatedAt = 100, lastPageIndex = 99)))

        // Deterministic given a fixed input order -- not meaningful beyond that, since there's
        // no per-device tiebreak field (PLAN.md §10).
        assertEquals(
            listOf(volume(number = 5.0, completed = false, updatedAt = 100, lastPageIndex = 10)),
            winner(listOf(first, second)).volumes,
        )
    }

    @Test
    fun winner_prefers_a_newer_completion_over_a_stale_inProgress_entry() {
        val stillInProgress = record(volumes = listOf(volume(number = 5.0, completed = false, updatedAt = 100, lastPageIndex = 10)))
        val finishedElsewhere = record(volumes = listOf(volume(number = 5.0, completed = true, updatedAt = 200)))

        val merged = winner(listOf(stillInProgress, finishedElsewhere))

        assertEquals(
            listOf(volume(number = 5.0, completed = true, updatedAt = 200)),
            merged.volumes,
            "a stale in-progress marker must never resurrect as the winner once another device has finished it",
        )
    }

    @Test
    fun winner_lets_a_fresher_local_unread_retract_a_stale_remote_completion() {
        // The exact scenario reported live: a series was fully read and synced long ago: the
        // remote backup still says complete. The device resets that chapter back to unread
        // (a real, fresh local write) -- that fresher write must win, not get silently
        // overridden back to "complete" by the old backup on the next sync (PLAN.md §10).
        val staleRemoteCompletion = record(volumes = listOf(volume(number = 1.0, completed = true, updatedAt = 1_000)))
        val freshLocalUnread = record(volumes = listOf(volume(number = 1.0, completed = false, updatedAt = 50_000, lastPageIndex = 0)))

        val merged = winner(listOf(staleRemoteCompletion, freshLocalUnread))

        assertEquals(
            listOf(volume(number = 1.0, completed = false, updatedAt = 50_000, lastPageIndex = 0)),
            merged.volumes,
            "an explicit un-read is a real write and must be able to win a merge, same as a completion",
        )
    }
}

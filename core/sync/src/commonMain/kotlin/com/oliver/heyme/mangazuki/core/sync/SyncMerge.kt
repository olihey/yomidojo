package com.oliver.heyme.mangazuki.core.sync

/**
 * Partitions every record touching a sync pass (`local ∪ remote`) into groups that all refer
 * to the same real series, so [winner] can then run once per group. Two passes, not a single
 * keyed lookup (PLAN.md §10):
 *
 *  1. Hard grouping (cases 1 & 2): group by (provider, externalId) where both are non-null.
 *     Two records land in the same group only if they're EQUAL on this key -- same-provider
 *     disagreement never merges here, by construction of a plain grouping.
 *  2. Title bridge (case 3): treat each hard group as one unit (its members already agree on
 *     provider/externalId, though not necessarily on raw title text) plus every still-unresolved
 *     record, then group by normalizedTitle. A title bucket that contains two DIFFERENT hard
 *     groups sharing the same provider is a same-provider conflict (case 2) hiding inside a
 *     title match -- refuse to bridge ANYTHING in that bucket rather than guess which side is
 *     "right" (worked example: a title bucket containing (ANILIST, 16498), (ANILIST, 99999),
 *     and one untagged title-only record never bridges any of the three).
 */
fun resolveSyncGroups(records: List<SeriesProgressRecord>): List<List<SeriesProgressRecord>> {
    val (hard, unresolved) = records.partition { it.key.provider != null && it.key.externalId != null }
    val hardGroups: List<List<SeriesProgressRecord>> = hard
        .groupBy { it.key.provider to it.key.externalId }
        .values.toList()

    data class Unit(val titleKey: String, val members: List<SeriesProgressRecord>)
    val units = hardGroups.map { Unit(it.first().key.normalizedTitle, it) } +
        unresolved.map { Unit(it.key.normalizedTitle, listOf(it)) }

    return units.groupBy { it.titleKey }.values.flatMap { bucket ->
        val providerConflict = bucket.flatMap { it.members }
            .filter { it.key.provider != null }
            .groupBy { it.key.provider }
            .any { (_, group) -> group.map { it.key.externalId }.distinct().size > 1 }

        if (providerConflict) bucket.map { it.members } else listOf(bucket.flatMap { it.members })
    }
}

/**
 * Merges a group of [SeriesProgressRecord]s referring to the same real series (PLAN.md §10) —
 * unlike the old per-chapter design, there's no single "winning" record to pick wholesale:
 *
 *  - [SeriesProgressRecord.completedVolumes] is unioned across every record in the group.
 *    Completion is monotonic (a device never un-reads a chapter behind sync's back), so a
 *    plain union is always safe and needs no timestamp to arbitrate.
 *  - [SeriesProgressRecord.inProgressVolumes] is taken wholesale from whichever record has the
 *    newest [SeriesProgressRecord.updatedAt] (last-write-wins for the *whole list* at once, not
 *    per entry — there's no per-device tiebreak field anymore, so an exact tie keeps whichever
 *    record [Iterable.maxByOrNull] encounters first, which is deterministic given a fixed input
 *    order but not meaningful beyond that), then filtered to drop any entry that the merged
 *    [completedVolumes] now covers -- an in-progress marker from the losing/older side must
 *    never resurrect a chapter another device has since finished.
 *  - The merged [SeriesProgressRecord.key] and [SeriesProgressRecord.updatedAt] both come from
 *    whichever record is newest, same as [completedVolumes]'s union doesn't touch identity.
 */
fun winner(group: List<SeriesProgressRecord>): SeriesProgressRecord {
    val completedMerged = group.flatMap { it.completedVolumes }.distinct()
    val newest = group.reduce { a, b -> if (b.updatedAt > a.updatedAt) b else a }
    val inProgressMerged = newest.inProgressVolumes.filterNot { volume ->
        completedMerged.any { it.volume == volume.volume && it.number == volume.number }
    }
    return SeriesProgressRecord(
        key = newest.key,
        completedVolumes = completedMerged,
        inProgressVolumes = inProgressMerged,
        updatedAt = group.maxOf { it.updatedAt },
    )
}

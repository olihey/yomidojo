package com.oliver.heyme.mangazuki.core.sync

/** The bridge key used when a hard (provider, externalId) match isn't available (PLAN.md
 * §10, case 3) — plain equality on already-normalized/parsed fields, no fuzzy matching. */
private fun ProgressKey.titleTriple() = Triple(normalizedTitle, volume, number)

/**
 * Partitions every record touching a sync pass (`local ∪ remote`) into groups that all refer
 * to the same real chapter, so last-write-wins ([winner]) can then run once per group.
 * Two passes, not a single keyed lookup (PLAN.md §10):
 *
 *  1. Hard grouping (cases 1 & 2): group by (provider, externalId) where both are non-null.
 *     Two records land in the same group only if they're EQUAL on this key -- same-provider
 *     disagreement never merges here, by construction of a plain grouping.
 *  2. Title bridge (case 3): treat each hard group as one unit (its members already agree on
 *     title/volume/number) plus every still-unresolved record, then group by
 *     (normalizedTitle, volume, number). A title bucket that contains two DIFFERENT hard
 *     groups sharing the same provider is a same-provider conflict (case 2) hiding inside a
 *     title match -- refuse to bridge ANYTHING in that bucket rather than guess which side is
 *     "right" (worked example: a title bucket containing (ANILIST, 16498), (ANILIST, 99999),
 *     and one untagged title-only record never bridges any of the three).
 */
fun resolveSyncGroups(records: List<ProgressRecord>): List<List<ProgressRecord>> {
    val (hard, unresolved) = records.partition { it.key.provider != null && it.key.externalId != null }
    val hardGroups: List<List<ProgressRecord>> = hard
        .groupBy { it.key.provider to it.key.externalId }
        .values.toList()

    data class Unit(val titleKey: Triple<String, Double?, Double?>, val members: List<ProgressRecord>)
    val units = hardGroups.map { Unit(it.first().key.titleTriple(), it) } +
        unresolved.map { Unit(it.key.titleTriple(), listOf(it)) }

    return units.groupBy { it.titleKey }.values.flatMap { bucket ->
        val providerConflict = bucket.flatMap { it.members }
            .filter { it.key.provider != null }
            .groupBy { it.key.provider }
            .any { (_, group) -> group.map { it.key.externalId }.distinct().size > 1 }

        if (providerConflict) bucket.map { it.members } else listOf(bucket.flatMap { it.members })
    }
}

/** Last-write-wins within a group, tiebroken by [ProgressRecord.deviceId] descending on an
 * exact [ProgressRecord.updatedAt] tie. The tiebreak is needed for determinism, not because
 * `deviceId` carries any meaning of its own: `markSeriesProgress`/`markChaptersProgress`
 * (`LibraryRepository`) stamp every row in a bulk mark-as-read with one shared timestamp, so
 * exact ties are routine, not a rare edge case. Without a deterministic tiebreak, two devices
 * computing the same merge independently could each pick a different winner for a tied key
 * and never converge. */
fun winner(group: List<ProgressRecord>): ProgressRecord =
    group.maxWith(compareBy({ it.updatedAt }, { it.deviceId }))

package com.oliver.heyme.yomidojo.core.sync

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
 * Merges a group of [SeriesProgressRecord]s referring to the same real series (PLAN.md §10,
 * revised 2026-07-07) — genuinely per-chapter last-write-wins, replacing the old "completion is
 * monotonic, union forever" design:
 *
 *  - Every [VolumeProgress] across the group, from every record, is bucketed by its (volume,
 *    number) key. Within a bucket, whichever entry has the newest `updatedAt` wins outright —
 *    completed or not. An explicit un-read is therefore just a fresher write that beats a stale
 *    completed entry the same way a completion beats a stale in-progress one; there's no special
 *    case for either direction. An exact `updatedAt` tie keeps whichever entry
 *    [Iterable.reduce] encounters first, deterministic given a fixed input order but not
 *    meaningful beyond that (no per-device tiebreak field).
 *  - The merged [SeriesProgressRecord.key] comes from whichever input record has the latest
 *    entry overall (by its own volumes' max `updatedAt`) — only matters for which raw title
 *    spelling survives when bridging title-only records (case 3); a record with no volumes at
 *    all never contributes anything to the merge either way.
 */
fun winner(group: List<SeriesProgressRecord>): SeriesProgressRecord {
    val merged = group.flatMap { it.volumes }
        .groupBy { it.volume to it.number }
        .values
        .map { entries -> entries.reduce { a, b -> if (b.updatedAt > a.updatedAt) b else a } }
    val newestKeyRecord = group.reduce { a, b ->
        val aMax = a.volumes.maxOfOrNull { it.updatedAt } ?: Long.MIN_VALUE
        val bMax = b.volumes.maxOfOrNull { it.updatedAt } ?: Long.MIN_VALUE
        if (bMax > aMax) b else a
    }
    return SeriesProgressRecord(key = newestKeyRecord.key, volumes = merged)
}

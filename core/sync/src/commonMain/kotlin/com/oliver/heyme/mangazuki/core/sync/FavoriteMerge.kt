package com.oliver.heyme.mangazuki.core.sync

/**
 * One winner per series (PLAN.md §10 favorites sync). Grouping is by
 * [FavoriteRecord.normalizedTitle] alone -- it's the one key component every record has, and
 * it's what `resolveLocalSeriesId`'s fallback resolves by; the provider identity still rides
 * along on the winning record for the primary resolution path. Same last-write-wins
 * (updatedAt, then deviceId) tiebreak as [resolveAliasWinners], for the same reason: two
 * devices resolving independently must arrive at the same answer to ever converge. A newer
 * un-favorite ([FavoriteRecord.favorited] false) beats an older favorite exactly like any
 * other write -- that's the whole point of the explicit tombstone.
 */
fun resolveFavoriteWinners(records: List<FavoriteRecord>): List<FavoriteRecord> =
    records.groupBy { it.normalizedTitle }
        .values
        .map { group -> group.maxWith(compareBy({ it.updatedAt }, { it.deviceId })) }

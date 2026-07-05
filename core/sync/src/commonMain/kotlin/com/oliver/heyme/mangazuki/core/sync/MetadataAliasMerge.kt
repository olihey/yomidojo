package com.oliver.heyme.mangazuki.core.sync

/**
 * One winner per distinct [MetadataAliasRecord.normalizedTitle] (PLAN.md §10). Unlike progress
 * sync's three-case matching, an alias's key IS its title, so grouping is a plain groupBy --
 * no bridging pass needed. Same last-write-wins tiebreak as [winner], for the same reason:
 * two devices resolving independently must arrive at the same answer to ever converge.
 */
fun resolveAliasWinners(records: List<MetadataAliasRecord>): List<MetadataAliasRecord> =
    records.groupBy { it.normalizedTitle }
        .values
        .map { group -> group.maxWith(compareBy({ it.updatedAt }, { it.deviceId })) }

/**
 * Fills in a still-unmatched record's (provider, externalId) from a known alias for its
 * [ProgressKey.normalizedTitle] (PLAN.md §10) -- so [resolveSyncGroups]'s hard-match pass can
 * group it with another device's already-matched records for the same real series, even when
 * this device hasn't matched that series (or scanned it under the exact same raw title) itself.
 * Leaves [ProgressKey.normalizedTitle] untouched: [ProgressRecord]s are always applied at the
 * end against a device's OWN local chapters via `resolveLocalChapterId`, whose title fallback
 * still needs the record's real (unbridged) title to find anything there. Already-matched
 * records (real provider present) pass through unchanged -- bridging only ever helps the
 * fallback path, never overrides a genuine match.
 */
fun ProgressRecord.bridgedWith(aliases: List<MetadataAliasRecord>): ProgressRecord {
    if (key.provider != null) return this
    val alias = aliases.find { it.normalizedTitle == key.normalizedTitle } ?: return this
    return copy(key = key.copy(provider = alias.provider, externalId = alias.externalId))
}

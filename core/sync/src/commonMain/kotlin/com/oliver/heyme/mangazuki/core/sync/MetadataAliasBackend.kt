package com.oliver.heyme.mangazuki.core.sync

/**
 * Cross-device metadata-fix history (PLAN.md §10). Every manual Fix Metadata action records
 * the series' raw scanned title exactly as it stood right before the fix, paired with the
 * (provider, externalId) the user confirmed. Synced as its own file, separate from
 * `progress.json`, and consulted during progress sync ([bridgedWith]) to bridge devices that
 * haven't matched that series -- or scanned it under a different raw title -- themselves yet,
 * beyond what plain title equality (`SyncMerge`'s case 3) can reach on its own.
 */
interface MetadataAliasBackend {
    suspend fun pullAliases(): List<MetadataAliasRecord>
    suspend fun pushAliases(aliases: List<MetadataAliasRecord>)
}

data class MetadataAliasRecord(
    val normalizedTitle: String, // the series' sort_title as it stood right before the fix
    val provider: String,        // "ANILIST" | "KITSU" -- always set, unlike ProgressKey.provider
    val externalId: String,
    val updatedAt: Long,
    val deviceId: String,
)

/** A no-op backend for tests/call sites that don't care about metadata-alias sync (PLAN.md
 * §10) -- keeps [com.oliver.heyme.mangazuki.ProgressSyncCoordinator]'s existing callers/tests
 * compiling without threading a fake through everywhere. */
object NoOpMetadataAliasBackend : MetadataAliasBackend {
    override suspend fun pullAliases(): List<MetadataAliasRecord> = emptyList()
    override suspend fun pushAliases(aliases: List<MetadataAliasRecord>) {}
}

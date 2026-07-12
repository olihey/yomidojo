package com.oliver.heyme.mangazuki.core.sync

/**
 * Cross-device favorite series (PLAN.md §10). Each record is one series' heart state keyed by
 * the same device-independent identity progress records use ([SeriesKey]'s fields): provider +
 * externalId when matched, the frozen normalized title as the always-present fallback. Synced
 * as its own file (`favorites.json`), separate from `progress.json`/`metadata_aliases.json`.
 *
 * [FavoriteRecord.favorited] false records exist and sync too: an un-favorite is an explicit
 * tombstone with its own [FavoriteRecord.updatedAt], not record absence -- otherwise a stale
 * remote "favorited" would resurrect it on the next pull, exactly the failure progress sync's
 * v2 had with un-read (PLAN.md §10, v3 lesson).
 */
interface FavoritesBackend {
    suspend fun pullFavorites(): List<FavoriteRecord>
    suspend fun pushFavorites(favorites: List<FavoriteRecord>)
}

data class FavoriteRecord(
    val normalizedTitle: String, // series.sort_title (frozen normalization, §10)
    val provider: String?,       // "ANILIST" | "KITSU" | null (unmatched series still sync)
    val externalId: String?,
    val favorited: Boolean,
    val updatedAt: Long,
    val deviceId: String,
)

/** A no-op backend for tests/call sites that don't care about favorites sync, and the
 * substitution point when Settings' "Sync favorites" toggle is off -- same role as
 * [NoOpMetadataAliasBackend]. */
object NoOpFavoritesBackend : FavoritesBackend {
    override suspend fun pullFavorites(): List<FavoriteRecord> = emptyList()
    override suspend fun pushFavorites(favorites: List<FavoriteRecord>) {}
}

package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.FavoriteRow
import com.oliver.heyme.mangazuki.core.domain.MetadataAliasRow
import com.oliver.heyme.mangazuki.core.domain.SyncProgressRow
import com.oliver.heyme.mangazuki.core.sync.FavoriteRecord
import com.oliver.heyme.mangazuki.core.sync.FavoritesBackend
import com.oliver.heyme.mangazuki.core.sync.MetadataAliasBackend
import com.oliver.heyme.mangazuki.core.sync.MetadataAliasRecord
import com.oliver.heyme.mangazuki.core.sync.NoOpFavoritesBackend
import com.oliver.heyme.mangazuki.core.sync.NoOpMetadataAliasBackend
import com.oliver.heyme.mangazuki.core.sync.SeriesKey
import com.oliver.heyme.mangazuki.core.sync.SeriesProgressRecord
import com.oliver.heyme.mangazuki.core.sync.SyncBackend
import com.oliver.heyme.mangazuki.core.sync.VolumeProgress
import com.oliver.heyme.mangazuki.core.sync.bridgedWith
import com.oliver.heyme.mangazuki.core.sync.resolveAliasWinners
import com.oliver.heyme.mangazuki.core.sync.resolveFavoriteWinners
import com.oliver.heyme.mangazuki.core.sync.resolveSyncGroups
import com.oliver.heyme.mangazuki.core.sync.winner
import kotlinx.coroutines.sync.withLock

/**
 * Bridges `core:data` and `core:sync` (PLAN.md §10) — deliberately lives here rather than in
 * either module, since neither should depend on the other (a `LibraryRepository` that spoke
 * `core:sync` types would add a module edge this codebase's layering doesn't otherwise have).
 * [SyncProgressRow] (one per chapter, straight from SQL) is aggregated into one
 * [SeriesProgressRecord] per series at this boundary, the same way `AniListMetadataProvider`
 * converts its own DTOs to/from `RemoteWork` at its boundary.
 */
class ProgressSyncCoordinator(
    private val repository: LibraryRepository,
    private val backend: SyncBackend,
    // Metadata-fix history (PLAN.md §10) -- a separate Drive file/backend from progress, but
    // synced in the same pass since it's consulted to bridge progress records below. Defaults
    // to a no-op so existing callers/tests that don't care about it don't need a fake.
    private val aliasBackend: MetadataAliasBackend = NoOpMetadataAliasBackend,
    // Notifies (e.g. AppPreferences.recordSyncCompleted) once pull->merge->apply->push all
    // succeed -- called from here rather than duplicated at each of this class's two call sites
    // (MainActivity's foreground trigger, SyncWorker's periodic run).
    private val onSyncCompleted: () -> Unit = {},
    // Same idea as [onSyncCompleted] but for the alias half specifically (PLAN.md §10) -- always
    // invoked here regardless of which [aliasBackend] was passed in, since this class stays
    // agnostic to the "Sync fixed metadata" toggle. The call site decides whether this actually
    // records anything, passing a no-op when the toggle is off (same backend-substitution
    // pattern as [aliasBackend] itself) so the byline doesn't claim a sync that didn't happen.
    private val onAliasSyncCompleted: () -> Unit = {},
    // Favorite series (PLAN.md §10) -- third Drive file, same backend-substitution pattern:
    // the call site passes NoOpFavoritesBackend when Settings' "Sync favorites" is off.
    private val favoritesBackend: FavoritesBackend = NoOpFavoritesBackend,
    private val onFavoriteSyncCompleted: () -> Unit = {},
) {
    /** Wrapped in the same [libraryWriteMutex] as [LibrarySyncer.sync]/[MetadataEnricher.enrichPending]
     * — this writes `reading_progress` rows a concurrent rescan could also touch. */
    suspend fun sync() = libraryWriteMutex.withLock {
        val localAliases = repository.allMetadataAliases().map { it.toAliasRecord() }
        val remoteAliases = aliasBackend.pullAliases()
        val aliasWinners = resolveAliasWinners(localAliases + remoteAliases)

        val local = repository.allProgressForSync().toSeriesProgressRecords()
        val remote = backend.pull(null)
        // Bridging (PLAN.md §10) fills in a still-unmatched record's (provider, externalId)
        // from a known alias before grouping, so it can hard-match another device's already-
        // matched records for the same real series even when this device hasn't matched it (or
        // scanned it under a different raw title) itself -- beyond what plain title equality
        // (case 3) reaches on its own.
        val bridged = (local + remote).map { it.bridgedWith(aliasWinners) }
        val winners = resolveSyncGroups(bridged).map { winner(it) }

        winners.forEach { record ->
            record.volumes.forEach { volume ->
                // A chapter this device hasn't scanned yet (the file lives only on another
                // device) is simply skipped here -- it still round-trips through push() below
                // untouched, so a later device with that file can still apply it.
                val chapterId = repository.resolveLocalChapterId(
                    record.key.provider, record.key.externalId, record.key.normalizedTitle,
                    volume.volume, volume.number,
                ) ?: return@forEach
                // This device doesn't necessarily know the chapter's real page count at merge
                // time (it may not have opened it yet) -- Int.MAX_VALUE is a safe "fully read"
                // sentinel, since every reader/UI read of lastPageIndex already clamps to the
                // chapter's own actual page count (ReaderScreen's pager, SeriesScreen's percent
                // ring) rather than trusting it as an absolute index. Each entry's OWN updatedAt
                // is passed through here (not some series-wide aggregate) -- applyProgressIfNewer
                // uses it as that chapter's new local timestamp, and an inflated one would make a
                // chapter this sync didn't actually touch look fresher than it really is on the
                // next merge.
                val lastPageIndex = if (volume.completed) Int.MAX_VALUE else volume.lastPageIndex
                repository.applyProgressIfNewer(chapterId, lastPageIndex, volume.completed, volume.updatedAt, "")
            }
        }

        // Favorites (PLAN.md §10) -- structurally the alias half again: per-series records,
        // plain title-keyed LWW merge, apply locally where the series exists, push the full
        // reconciled set. A record for a series this device hasn't scanned round-trips through
        // the push untouched, same as an unresolvable progress record above.
        val localFavorites = repository.allFavoritesForSync().map { it.toFavoriteRecord() }
        val remoteFavorites = favoritesBackend.pullFavorites()
        val favoriteWinners = resolveFavoriteWinners(localFavorites + remoteFavorites)
        favoriteWinners.forEach { record ->
            val seriesId = repository.resolveLocalSeriesId(record.provider, record.externalId, record.normalizedTitle)
                ?: return@forEach
            repository.applyFavoriteIfNewer(seriesId, record.favorited, record.updatedAt, record.deviceId)
        }

        backend.push(winners)
        aliasBackend.pushAliases(aliasWinners)
        favoritesBackend.pushFavorites(favoriteWinners)
        onFavoriteSyncCompleted()
        onAliasSyncCompleted()
        onSyncCompleted()
    }
}

/** Groups this device's per-chapter progress rows into one [SeriesProgressRecord] per series
 * (PLAN.md §10) -- the local equivalent of what [SeriesRecordDto] represents on the wire. Each
 * row keeps its own `updatedAt` (2026-07-07) rather than collapsing to one timestamp per series
 * -- that per-chapter granularity is exactly what lets [winner] resolve an explicit un-read as a
 * real, individually-timestamped write instead of a series-wide aggregate swallowing it. */
private fun List<SyncProgressRow>.toSeriesProgressRecords(): List<SeriesProgressRecord> =
    groupBy { Triple(it.provider, it.externalId, it.normalizedTitle) }
        .map { (key, rows) ->
            val (provider, externalId, normalizedTitle) = key
            SeriesProgressRecord(
                key = SeriesKey(provider, externalId, normalizedTitle),
                volumes = rows.map { VolumeProgress(it.volume, it.number, it.completed, it.lastPageIndex, it.updatedAt) },
            )
        }

private fun MetadataAliasRow.toAliasRecord() = MetadataAliasRecord(
    normalizedTitle = normalizedOldTitle,
    provider = provider,
    externalId = externalId,
    updatedAt = updatedAt,
    // A locally-recorded alias always has a real deviceId (recordMetadataAlias requires one);
    // null only appears here in principle, same defensive default as SyncProgressRow.
    deviceId = deviceId ?: "",
)

private fun FavoriteRow.toFavoriteRecord() = FavoriteRecord(
    normalizedTitle = normalizedTitle,
    provider = provider,
    externalId = externalId,
    favorited = favorited,
    updatedAt = updatedAt,
    deviceId = deviceId ?: "",
)

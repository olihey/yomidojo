package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.MetadataAliasRow
import com.oliver.heyme.mangazuki.core.domain.SyncProgressRow
import com.oliver.heyme.mangazuki.core.sync.InProgressVolume
import com.oliver.heyme.mangazuki.core.sync.MetadataAliasBackend
import com.oliver.heyme.mangazuki.core.sync.MetadataAliasRecord
import com.oliver.heyme.mangazuki.core.sync.NoOpMetadataAliasBackend
import com.oliver.heyme.mangazuki.core.sync.SeriesKey
import com.oliver.heyme.mangazuki.core.sync.SeriesProgressRecord
import com.oliver.heyme.mangazuki.core.sync.SyncBackend
import com.oliver.heyme.mangazuki.core.sync.VolumeChapterKey
import com.oliver.heyme.mangazuki.core.sync.bridgedWith
import com.oliver.heyme.mangazuki.core.sync.resolveAliasWinners
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
            // A chapter this device hasn't scanned yet (the file lives only on another device)
            // is simply skipped here -- it still round-trips through push() below untouched, so
            // a later device with that file can still apply it.
            record.completedVolumes.forEach { volume ->
                val chapterId = repository.resolveLocalChapterId(
                    record.key.provider, record.key.externalId, record.key.normalizedTitle,
                    volume.volume, volume.number,
                ) ?: return@forEach
                // This device doesn't necessarily know the chapter's real page count at merge
                // time (it may not have opened it yet) -- Int.MAX_VALUE is a safe "fully read"
                // sentinel, since every reader/UI read of lastPageIndex already clamps to the
                // chapter's own actual page count (ReaderScreen's pager, SeriesScreen's percent
                // ring) rather than trusting it as an absolute index.
                repository.applyProgressIfNewer(chapterId, Int.MAX_VALUE, true, record.updatedAt, "")
            }
            record.inProgressVolumes.forEach { volume ->
                val chapterId = repository.resolveLocalChapterId(
                    record.key.provider, record.key.externalId, record.key.normalizedTitle,
                    volume.volume, volume.number,
                ) ?: return@forEach
                repository.applyProgressIfNewer(chapterId, volume.lastPageIndex, false, record.updatedAt, "")
            }
        }

        backend.push(winners)
        aliasBackend.pushAliases(aliasWinners)
        onAliasSyncCompleted()
        onSyncCompleted()
    }
}

/** Groups this device's per-chapter progress rows into one [SeriesProgressRecord] per series
 * (PLAN.md §10) -- the local equivalent of what [SeriesRecordDto] represents on the wire.
 * `updatedAt` is the max across the series' chapters: the single timestamp [winner] treats the
 * whole record's [SeriesProgressRecord.inProgressVolumes] list as having been written at. */
private fun List<SyncProgressRow>.toSeriesProgressRecords(): List<SeriesProgressRecord> =
    groupBy { Triple(it.provider, it.externalId, it.normalizedTitle) }
        .map { (key, rows) ->
            val (provider, externalId, normalizedTitle) = key
            SeriesProgressRecord(
                key = SeriesKey(provider, externalId, normalizedTitle),
                completedVolumes = rows.filter { it.completed }.map { VolumeChapterKey(it.volume, it.number) },
                inProgressVolumes = rows.filterNot { it.completed }
                    .map { InProgressVolume(it.volume, it.number, it.lastPageIndex) },
                updatedAt = rows.maxOf { it.updatedAt },
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

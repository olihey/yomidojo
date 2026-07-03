package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.SyncProgressRow
import com.oliver.heyme.mangazuki.core.sync.ProgressKey
import com.oliver.heyme.mangazuki.core.sync.ProgressRecord
import com.oliver.heyme.mangazuki.core.sync.SyncBackend
import com.oliver.heyme.mangazuki.core.sync.resolveSyncGroups
import com.oliver.heyme.mangazuki.core.sync.winner
import kotlinx.coroutines.sync.withLock

/**
 * Bridges `core:data` and `core:sync` (PLAN.md §10) — deliberately lives here rather than in
 * either module, since neither should depend on the other (a `LibraryRepository` that spoke
 * `core:sync` types would add a module edge this codebase's layering doesn't otherwise have).
 * [SyncProgressRow] <-> [ProgressRecord] conversion happens at this boundary, the same way
 * `AniListMetadataProvider` converts its own DTOs to/from `RemoteWork` at its boundary.
 */
class ProgressSyncCoordinator(
    private val repository: LibraryRepository,
    private val backend: SyncBackend,
) {
    /** Wrapped in the same [libraryWriteMutex] as [LibrarySyncer.sync]/[MetadataEnricher.enrichPending]
     * — this writes `reading_progress` rows a concurrent rescan could also touch. */
    suspend fun sync() = libraryWriteMutex.withLock {
        val local = repository.allProgressForSync().map { it.toProgressRecord() }
        val remote = backend.pull(null)
        val winners = resolveSyncGroups(local + remote).map { winner(it) }

        winners.forEach { record ->
            // A winner for a chapter this device hasn't scanned yet (the file lives only on
            // another device) is simply skipped here -- it still round-trips through push()
            // below untouched, so a later device with that file can still apply it.
            val chapterId = repository.resolveLocalChapterId(
                record.key.provider, record.key.externalId, record.key.normalizedTitle,
                record.key.volume, record.key.number,
            ) ?: return@forEach
            repository.applyProgressIfNewer(chapterId, record.lastPageIndex, record.completed, record.updatedAt, record.deviceId)
        }

        backend.push(winners)
    }
}

private fun SyncProgressRow.toProgressRecord() = ProgressRecord(
    key = ProgressKey(provider, externalId, normalizedTitle, volume, number),
    completed = completed,
    lastPageIndex = lastPageIndex,
    // A `reading_progress` row written before this feature existed has a null device_id
    // (the old hardcoded-null writes) -- "" always loses the merge tiebreak against any real
    // device id, a harmless, deterministic default rather than a crash.
    deviceId = deviceId ?: "",
    updatedAt = updatedAt,
)

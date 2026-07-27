package com.oliver.heyme.yomidojo.core.sync

/**
 * Cross-device read-status sync (PLAN.md §10). Keys are DEVICE-INDEPENDENT — never
 * the local row id (which derives from source+locator and differs per device). One record
 * per SERIES, not per chapter (2026-07-05) — [SeriesProgressRecord.volumes] carries every
 * chapter's state, keeping the wire file compact regardless of how many chapters a series has.
 * Matching two records as "the same series" is three cases (see [SeriesKey] and
 * `SyncMerge.resolveSyncGroups`):
 *   1. Same provider, ids equal            -> match (reliable path)
 *   2. Same provider, ids differ           -> never match, regardless of title
 *   3. Different providers, or id missing  -> fall back to normalizedTitle
 * Merge is per-chapter last-write-wins (2026-07-07, `SyncMerge.winner`) — each [VolumeProgress]
 * carries its own `updatedAt`, so an explicit un-read is just a fresher write that wins the same
 * way a completion does; there's no longer a "completion is monotonic" special case; a device
 * that means to reset its own read progress does actually win once that write is newer than
 * whatever's on Drive. Transport is pluggable; first realistic backend is the user's own Google
 * Drive (`appDataFolder`).
 */
interface SyncBackend {
    suspend fun pull(since: SyncCursor?): List<SeriesProgressRecord>
    suspend fun push(changes: List<SeriesProgressRecord>): SyncCursor
}

/** Device-independent identity for one series' read state. */
data class SeriesKey(
    // AniList and Kitsu ids are separate numbering spaces -- externalId alone would let an
    // unrelated id from a different provider collide as if it were the same series.
    val provider: String?,        // "ANILIST" | "KITSU" | null
    val externalId: String?,      // primary when matched; only comparable within the same provider
    val normalizedTitle: String,  // fallback key (frozen normalization, §10) = series.sort_title
)

/**
 * One chapter's read state as of [updatedAt] (PLAN.md §10, 2026-07-07) — the unit
 * `SyncMerge.winner` now resolves last-write-wins over, one entry per (volume, number) key.
 * `volume`/`number` mirror [com.oliver.heyme.yomidojo.core.domain.Chapter.volume]/`.number`'s
 * independent nullability (an unparseable filename can leave either unset). [lastPageIndex] is
 * meaningless when [completed] is true (kept anyway rather than modeled as a sealed type, since
 * every reader/UI read of it already clamps to the chapter's own real page count).
 */
data class VolumeProgress(
    val volume: Double?,
    val number: Double?,
    val completed: Boolean,
    val lastPageIndex: Int,
    val updatedAt: Long,
)

data class SeriesProgressRecord(
    val key: SeriesKey,
    val volumes: List<VolumeProgress>,
)

data class SyncCursor(val token: String)

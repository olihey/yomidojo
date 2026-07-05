package com.oliver.heyme.mangazuki.core.sync

/**
 * Cross-device read-status sync (PLAN.md §10). Keys are DEVICE-INDEPENDENT — never
 * the local row id (which derives from source+locator and differs per device). One record
 * per SERIES, not per chapter (2026-07-05) — [completedVolumes]/[inProgressVolumes] carry
 * every chapter's state, keeping the wire file compact regardless of how many chapters a
 * series has. Matching two records as "the same series" is three cases (see [SeriesKey] and
 * `SyncMerge.resolveSyncGroups`):
 *   1. Same provider, ids equal            -> match (reliable path)
 *   2. Same provider, ids differ           -> never match, regardless of title
 *   3. Different providers, or id missing  -> fall back to normalizedTitle
 * Merge unions [completedVolumes] (monotonic — once read, always read) and takes
 * [inProgressVolumes] wholesale from whichever side has the newer [SeriesProgressRecord.updatedAt]
 * (no per-device tiebreak field anymore, so an exact tie keeps whichever side is encountered
 * first). Transport is pluggable; first realistic backend is the user's own Google Drive
 * (`appDataFolder`).
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

/** One chapter's identity within a series — `number` mirrors [com.oliver.heyme.mangazuki.core.domain.Chapter.number]'s
 * nullability (an unparseable filename can leave it unset); `volume` is null for the common
 * flat-chapter-list case. */
data class VolumeChapterKey(
    val volume: Double?,
    val number: Double?,
)

/** A chapter that's been started but not finished, with its last-read page. */
data class InProgressVolume(
    val volume: Double?,
    val number: Double?,
    val lastPageIndex: Int,
)

data class SeriesProgressRecord(
    val key: SeriesKey,
    val completedVolumes: List<VolumeChapterKey>,
    val inProgressVolumes: List<InProgressVolume>,
    val updatedAt: Long,          // last-write-wins, applied to the whole record (see class doc)
)

data class SyncCursor(val token: String)

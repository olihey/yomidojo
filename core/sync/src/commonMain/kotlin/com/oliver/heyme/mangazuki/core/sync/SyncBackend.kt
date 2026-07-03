package com.oliver.heyme.mangazuki.core.sync

/**
 * Cross-device read-status sync (PLAN.md §10). Keys are DEVICE-INDEPENDENT — never
 * the local row id (which derives from source+locator and differs per device).
 * Matching two records as "the same chapter" is three cases, not one key comparison
 * (see [ProgressKey] and `SyncMerge.resolveSyncGroups`):
 *   1. Same provider, ids equal            -> match (reliable path)
 *   2. Same provider, ids differ           -> never match, regardless of title
 *   3. Different providers, or id missing  -> fall back to normalizedTitle+volume+number
 * Merge is per-group last-write-wins on `updatedAt`, tiebroken by `deviceId`. Transport is
 * pluggable; first realistic backend is the user's own Google Drive (`appDataFolder`).
 */
interface SyncBackend {
    suspend fun pull(since: SyncCursor?): List<ProgressRecord>
    suspend fun push(changes: List<ProgressRecord>): SyncCursor
}

/** Device-independent identity for one chapter's read state. */
data class ProgressKey(
    // AniList and Kitsu ids are separate numbering spaces -- externalId alone would let an
    // unrelated id from a different provider collide as if it were the same chapter.
    val provider: String?,        // "ANILIST" | "KITSU" | null
    val externalId: String?,      // primary when matched; only comparable within the same provider
    val normalizedTitle: String,  // fallback key (frozen normalization, §10) = series.sort_title
    val volume: Double?,
    val number: Double?,
)

data class ProgressRecord(
    val key: ProgressKey,
    val completed: Boolean,
    val lastPageIndex: Int,       // best-effort; files may differ across devices
    val updatedAt: Long,          // last-write-wins
    val deviceId: String,
)

data class SyncCursor(val token: String)

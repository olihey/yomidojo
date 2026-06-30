package com.mangaread.core.sync

/**
 * Cross-device read-status sync (PLAN.md §10). Keys are DEVICE-INDEPENDENT — never
 * the local row id (which derives from source+locator and differs per device).
 *   Primary:  external_id (AniList) + volume/chapter number
 *   Fallback: frozen-normalized sort_title + volume/chapter number (best-effort)
 * Merge is per-key last-write-wins on `updatedAt`. Transport is pluggable; first
 * realistic backend is the user's own cloud storage (no server, no accounts).
 */
interface SyncBackend {
    suspend fun pull(since: SyncCursor?): List<ProgressRecord>
    suspend fun push(changes: List<ProgressRecord>): SyncCursor
}

/** Device-independent identity for one chapter's read state. */
data class ProgressKey(
    val externalId: String?,      // primary when matched
    val normalizedTitle: String,  // fallback key (frozen normalization, §10)
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

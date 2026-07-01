package com.mangaread

import com.mangaread.core.domain.Chapter

/** Cached cover path (null if generation failed/skipped) plus the chapter's real page count. */
data class ChapterScanResult(val coverPath: String?, val pageCount: Int)

/**
 * Generates and caches a chapter's first-page cover during scan (PLAN.md §9), and reports its
 * real page count as a byproduct (used for the read-percentage overlay, PLAN.md §7.2). Covers
 * live in the OS-purgeable app cache (not app-internal storage), so the OS can reclaim them
 * under storage pressure at any time — callers must use [coverPathExists] before trusting a
 * previously-recorded cover_path and re-generate via [ensureCover] if it's gone.
 * Implementations must be idempotent: if the cached cover file already exists, its path is
 * returned without regenerating so a re-scan doesn't redo the (expensive) image work — but the
 * page count is still counted every time since that's cheap (no image bytes read).
 */
interface ChapterCoverCache {
    /** Null only if the chapter's source couldn't be read at all (e.g. no images found). */
    suspend fun ensureCover(chapter: Chapter): ChapterScanResult?

    /** True if [path] (a previously-recorded cover_path) still exists and is non-empty. */
    fun coverPathExists(path: String): Boolean
}

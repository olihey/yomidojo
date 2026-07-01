package com.mangaread

import com.mangaread.core.domain.Chapter

/** Cached cover path (null if generation failed/skipped) plus the chapter's real page count. */
data class ChapterScanResult(val coverPath: String?, val pageCount: Int)

/**
 * Generates and caches a chapter's first-page cover during scan (PLAN.md §9), and reports its
 * real page count as a byproduct (used for the read-percentage overlay, PLAN.md §7.2).
 * Implementations must be idempotent: if the cached cover file already exists, its path is
 * returned without regenerating so a re-scan doesn't redo the (expensive) image work — but the
 * page count is still counted every time since that's cheap (no image bytes read).
 */
interface ChapterCoverCache {
    /** Null only if the chapter's source couldn't be read at all (e.g. no images found). */
    suspend fun ensureCover(chapter: Chapter): ChapterScanResult?
}

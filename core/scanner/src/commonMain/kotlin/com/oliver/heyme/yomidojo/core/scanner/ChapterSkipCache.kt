package com.oliver.heyme.yomidojo.core.scanner

/**
 * Lets a rescan skip the expensive part of processing a file it has already seen and hasn't
 * changed since (PLAN.md §5) -- chiefly opening a CBZ just to check `ComicInfo.xml`, and
 * re-listing an image-folder chapter just to recount its pages. Implemented in `composeApp`
 * (over [com.oliver.heyme.yomidojo.core.data.LibraryRepository]) so this module doesn't need a
 * dependency on `core:data`'s SQLDelight types.
 */
interface ChapterSkipCache {
    /** Null means this chapter id has never been scanned before -- always a cache miss. */
    suspend fun lookup(chapterId: String): CachedChapter?
}

/**
 * What was already known about a chapter as of the last successful scan. [changeToken] is
 * compared against the file's current one by the caller -- a mismatch means the file changed on
 * disk and everything else here is stale, so it's still a miss despite the id being known.
 */
data class CachedChapter(
    val changeToken: String?,
    /** The series this chapter belonged to last scan -- only meaningful for a root-level file,
     * where the series is resolved per-file rather than fixed by a containing folder (see
     * [LibraryScanner]'s root-level loop). Ignored for a folder-contained chapter. */
    val seriesId: String,
    val seriesTitle: String,
    val displayName: String,
    val pageCount: Int?,
)

/** Forces every file to be fully reprocessed -- the pre-skip-cache behavior, used when a caller
 * has no persisted chapter data to check against (e.g. tests not exercising the cache). */
object NoOpChapterSkipCache : ChapterSkipCache {
    override suspend fun lookup(chapterId: String): CachedChapter? = null
}

package com.mangaread.core.data

/** A series as shown in the library grid/list, with derived counts. */
data class LibraryCard(
    val id: String,
    val title: String,
    val sortTitle: String,
    val author: String?,
    val coverPath: String?,
    val chapterCount: Int,
    val unreadCount: Int,
    val latestChapterAdded: Long,
    val latestRead: Long?,
    /**
     * Coil model for the cover. A real cached cover path if present, else a scheme-tagged
     * locator the platform cover fetcher resolves: "cbz:<uri>" (first image in the archive)
     * or "imgdir:<uri>" (first image in the folder). Null if the series has no chapters.
     */
    val coverModel: String?,
)

/** A chapter row for the series screen, with derived read state (PLAN.md §7.3). */
data class ChapterCard(
    val id: String,
    val seriesId: String,
    val sourceId: String,
    val locator: String,
    val format: String,
    val displayName: String,
    val volume: Double?,
    val number: Double?,
    val pageCount: Int?,
    val lastPageIndex: Int,
    val completed: Boolean,
    /** Cached first-page cover if generated, else the scheme-tagged locator fallback (§9). */
    val coverModel: String?,
)

/** Known scan-time state for one chapter, used to decide whether a re-scan can skip it entirely. */
data class ChapterCoverState(val coverPath: String?, val pageCount: Int?)

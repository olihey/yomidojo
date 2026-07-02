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
    /** AniList `startDate.year`, once matched (PLAN.md §9) — drives the "release start" sort. */
    val startYear: Int?,
    /** Non-null once AniList-matched (PLAN.md §9) — drives the library metadata-status badge. */
    val externalId: String?,
    /** Set when enrichment ran but found no good-enough match (PLAN.md §9.2) — badge shows "✕"
     * instead of "?" for a series that's been tried and failed, vs. never queued yet. */
    val metadataCheckedAt: Long?,
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
    /** Live-extracted cover via the scheme-tagged locator fallback (§9); generated on demand. */
    val coverModel: String?,
)

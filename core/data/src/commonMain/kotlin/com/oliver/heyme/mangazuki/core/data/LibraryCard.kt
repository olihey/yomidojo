package com.oliver.heyme.mangazuki.core.data

/** A series as shown in the library grid/list, with derived counts. */
data class LibraryCard(
    val id: String,
    val title: String,
    val sortTitle: String,
    val author: String?,
    val coverPath: String?,
    val chapterCount: Int,
    val unreadCount: Int,
    /** Chapters someone is mid-read in (pages recorded, not completed) — see [isInProgress]. */
    val startedCount: Int,
    /** User's heart toggle (PLAN.md §10 favorites sync) — grid heart badge + favorites filter. */
    val favorite: Boolean,
    /** When the heart was last toggled — orders Your Page's Favorites shelf (recent first).
     * Null when never touched. */
    val favoriteUpdatedAt: Long?,
    val latestChapterAdded: Long,
    val latestRead: Long?,
    /** AniList `startDate.year`, once matched (PLAN.md §9) — drives the "release start" sort. */
    val startYear: Int?,
    /** AniList MediaStatus: FINISHED, RELEASING, ... (PLAN.md §9) — same field as `Series.status`,
     * shown as the same colored-dot badge in the detailed library layout as on the series header. */
    val status: String?,
    /** Non-null once AniList-matched (PLAN.md §9) — drives the library metadata-status badge. */
    val externalId: String?,
    /** Set when enrichment ran but found no good-enough match (PLAN.md §9.2) — badge shows "✕"
     * instead of "?" for a series that's been tried and failed, vs. never queued yet. */
    val metadataCheckedAt: Long?,
    /** AniList's per-language titles, once matched (PLAN.md §9) — feed the "series title"
     * display setting; each is null if AniList didn't have that language for this work. */
    val titleRomaji: String?,
    val titleEnglish: String?,
    val titleNative: String?,
    /**
     * Coil model for the cover. A real cached cover path if present, else a scheme-tagged
     * locator the platform cover fetcher resolves: "cbz:<uri>" (first image in the archive)
     * or "imgdir:<uri>" (first image in the folder). Null if the series has no chapters.
     */
    val coverModel: String?,
) {
    /** "Currently being read": not finished, and with real progress — either some chapters
     * completed, or at least one chapter mid-read ([startedCount], which covers a series whose
     * very first chapter is only partially read and would otherwise not count). One definition
     * for the Your Page "Jump back in"/"On your shelf" feeds, the library's "Show in progress"
     * filter, and the shelf card's CONTINUE badge. */
    val isInProgress: Boolean
        get() = chapterCount > 0 && unreadCount > 0 && (unreadCount < chapterCount || startedCount > 0)
}

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
    val size: Long?,
    val lastPageIndex: Int,
    val completed: Boolean,
    /** Live-extracted cover via the scheme-tagged locator fallback (§9); generated on demand. */
    val coverModel: String?,
)

/** A chapter for the "Your Page" dashboard's "Fresh chapters" feed (most recently added across
 * the whole library, not scoped to one series like [ChapterCard]) -- carries its series' title in
 * every language so the feed can respect the same "series title" display setting as everywhere
 * else, without a second query per card. */
data class RecentChapterCard(
    val chapterId: String,
    val seriesId: String,
    val seriesTitle: String,
    val seriesTitleRomaji: String?,
    val seriesTitleEnglish: String?,
    val seriesTitleNative: String?,
    val displayName: String,
    val volume: Double?,
    val number: Double?,
    val dateAdded: Long,
    val coverModel: String?,
)

/** A chapter's last-known scan state, for the scanner's skip-cache (§5) to decide whether a file
 * needs full reprocessing. [seriesTitle] is that chapter's series' current `title` -- only
 * meaningful for a root-level file, whose series is resolved per-file rather than fixed by a
 * containing folder. */
data class ChapterSkipRow(
    val changeToken: String?,
    val seriesId: String,
    val seriesTitle: String,
    val displayName: String,
    val pageCount: Int?,
)

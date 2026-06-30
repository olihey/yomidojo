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
    /**
     * Coil model for the cover. A real cached cover path if present, else a scheme-tagged
     * locator the platform cover fetcher resolves: "cbz:<uri>" (first image in the archive)
     * or "imgdir:<uri>" (first image in the folder). Null if the series has no chapters.
     */
    val coverModel: String?,
)

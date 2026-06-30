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
)

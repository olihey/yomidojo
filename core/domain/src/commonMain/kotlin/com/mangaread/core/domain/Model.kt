package com.mangaread.core.domain

/** Chapter container format. PDF reserved but not built (PLAN.md §11, §16). */
enum class ChapterFormat { IMAGE_DIR, CBZ /*, PDF */ }

/** Reading modes — manga defaults to PAGED_RTL; direction flows through the pager. */
enum class ReadingMode { PAGED_LTR, PAGED_RTL, VERTICAL_PAGED, VERTICAL_CONTINUOUS }

enum class ReadingDirection { LTR, RTL }

/** Optional source capabilities (PLAN.md §6) — promise only what the weakest backend has. */
enum class SourceCapability { RANDOM_ACCESS, DELTA_SYNC, WATCH }

data class Source(
    val id: String,
    val type: String,            // LOCAL | ONEDRIVE | WEBDAV ...
    val displayName: String,
    val configJson: String,      // granted root (SAF URI / iOS bookmark), creds ref
    val syncToken: String? = null,
)

data class Series(
    val id: String,              // deterministic: hash(source_id + normalized locator)
    val title: String,
    val sortTitle: String,       // frozen-normalized; also the sync fallback key (PLAN.md §10)
    val author: String? = null,
    val description: String? = null,
    val coverPath: String? = null,
    val startYear: Int? = null,
    val readingDirection: ReadingDirection? = null,
    val externalId: String? = null,   // AniList Media id; primary sync key
    val dateAdded: Long,
    val lastScanned: Long? = null,
)

data class Chapter(
    val id: String,              // deterministic: hash(source_id + normalized locator)
    val seriesId: String,
    val sourceId: String,
    val locator: String,
    val format: ChapterFormat,
    val displayName: String,
    val volume: Double? = null,  // from the PARSER (null → flat list)
    val number: Double? = null,  // from the PARSER; supports 12.5
    val pageCount: Int? = null,
    val size: Long? = null,
    val changeToken: String? = null,
    val dateAdded: Long,
)

data class ReadingProgress(
    val chapterId: String,
    val lastPageIndex: Int = 0,
    val completed: Boolean = false,
    val updatedAt: Long,         // recently-read sort AND sync last-write-wins
    val deviceId: String? = null,
)

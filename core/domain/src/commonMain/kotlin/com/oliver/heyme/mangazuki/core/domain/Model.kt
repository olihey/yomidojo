package com.oliver.heyme.mangazuki.core.domain

/** Chapter container format. PDF reserved but not built (PLAN.md §11, §16). */
enum class ChapterFormat { IMAGE_DIR, CBZ /*, PDF */ }

/** Reading modes — manga defaults to PAGED_RTL; direction flows through the pager. */
enum class ReadingMode { PAGED_LTR, PAGED_RTL, VERTICAL_PAGED, VERTICAL_CONTINUOUS }

enum class ReadingDirection { LTR, RTL }

/** Optional source capabilities (PLAN.md §6) — promise only what the weakest backend has.
 * RANGE_READ means seeking within a file is cheap enough that a reader shouldn't download
 * the whole thing up front (PLAN.md §6.1/§11) — true for SMB, not worth it for local SAF. */
enum class SourceCapability { RANDOM_ACCESS, DELTA_SYNC, WATCH, RANGE_READ }

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
    // AniList's per-language titles, once matched (PLAN.md §9) — feed the "series title"
    // display setting; each is null if AniList didn't have that language for this work.
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val titleNative: String? = null,
    // Remaining AniList fields worth keeping locally (PLAN.md §9), all null until matched.
    val status: String? = null,        // AniList MediaStatus: FINISHED, RELEASING, ...
    val format: String? = null,        // AniList MediaFormat: MANGA, NOVEL, ONE_SHOT, ...
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val isAdult: Boolean = false,
    val averageScore: Int? = null,     // 0-100
    val siteUrl: String? = null,
    val bannerPath: String? = null,    // app-internal storage path for the downloaded banner
    val metadataProvider: String? = null, // which provider matched this: ANILIST | KITSU (§9.3)
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

/**
 * One chapter's progress plus its cross-device sync identity (PLAN.md §10) -- deliberately
 * plain-primitive-typed (no `core:sync` types here) so `core:data` doesn't need a dependency
 * on `core:sync`; `composeApp`'s `ProgressSyncCoordinator` converts this to/from
 * `core.sync.ProgressRecord` at the boundary, the same way a `MetadataProvider` converts its
 * own DTOs to/from `RemoteWork`.
 */
data class SyncProgressRow(
    val provider: String?,        // series.metadata_provider: "ANILIST" | "KITSU" | null
    val externalId: String?,      // series.external_id
    val normalizedTitle: String,  // series.sort_title (frozen normalization, §10)
    val volume: Double?,
    val number: Double?,
    val completed: Boolean,
    val lastPageIndex: Int,
    val updatedAt: Long,
    val deviceId: String?,
)

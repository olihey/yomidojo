package com.oliver.heyme.mangazuki.core.metadata

/**
 * Series-level metadata only (PLAN.md §9). Chapters/volumes always come from the
 * parser, never here. AniList is the single provider — public GraphQL, no API key,
 * called per-device with a serialized, rate-limited, 429-backing-off queue (§9.2).
 * `search` also powers the user-facing "Fix metadata" re-match (§9.1).
 */
interface MetadataProvider {
    suspend fun search(title: String): List<RemoteWork>
    suspend fun details(externalId: String): RemoteWorkDetails
}

data class RemoteWork(
    val externalId: String,
    val title: String,          // preferred title: english ?: romaji ?: native
    /** Per-language titles, so Fix Metadata's search results can respect the "series title"
     * display setting (PLAN.md §9) instead of always showing [title]'s fixed pick. Null when
     * the provider doesn't have that language for this result. */
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val titleNative: String? = null,
    val coverUrl: String?,
    val startYear: Int?,
    /** Canonical format string, normalized to AniList's MediaFormat values (MANGA, NOVEL,
     * ONE_SHOT, ...) regardless of provider — see [RemoteWorkDetails.format]. Shown as a byline
     * in the Fix Metadata search results so a result's type is visible before applying it. */
    val format: String? = null,
)

data class RemoteWorkDetails(
    val externalId: String,
    val title: String,          // preferred title: english ?: romaji ?: native
    val titleRomaji: String?,
    val titleEnglish: String?,
    val titleNative: String?,
    val author: String?,
    val description: String?,   // HTML stripped, "(Source: …)" removed
    val coverUrl: String?,
    val startYear: Int?,
    /** Canonical status string, normalized to AniList's MediaStatus values (FINISHED,
     * RELEASING, NOT_YET_RELEASED, CANCELLED, HIATUS) regardless of which provider this
     * came from — so downstream code (StatusRow, sorting) never needs to know the source. */
    val status: String?,
    /** Canonical format string, normalized to AniList's MediaFormat values (MANGA, NOVEL,
     * ONE_SHOT, ...) regardless of provider — see [status]. */
    val format: String?,
    val genres: List<String>,
    val tags: List<String>,
    val isAdult: Boolean,
    val averageScore: Int?,     // 0-100, null if not enough ratings
    val siteUrl: String?,
    val bannerUrl: String?,
    /** Which provider this came from ("ANILIST"/"KITSU") — stored on the series so the UI
     * can show attribution and Fix Metadata can default back to the right one. */
    val providerId: String,
)

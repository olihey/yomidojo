package com.mangaread.core.metadata

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
    val title: String,
    val coverUrl: String?,
    val startYear: Int?,
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
)

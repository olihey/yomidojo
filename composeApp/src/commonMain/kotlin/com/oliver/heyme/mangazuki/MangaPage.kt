package com.oliver.heyme.mangazuki

/**
 * Coil model for one reader page. `model` is the chapter's scheme-tagged locator
 * ("cbz:<uri>" / "imgdir:<uri>", same scheme as [MangaCover]); `index` selects the page
 * within it. A dedicated data class — not a String — so Coil's built-in String→Uri mapper
 * doesn't intercept it before the custom fetcher runs (see MangaCover).
 *
 * `size` is the chapter file's size (`ChapterCard.size`) — lets `PageFetcher` use a range
 * read instead of downloading the whole CBZ over a network source (PLAN.md §6.2); null is
 * always safe (falls back to reading the whole file).
 */
data class MangaPage(val model: String, val index: Int, val size: Long? = null)

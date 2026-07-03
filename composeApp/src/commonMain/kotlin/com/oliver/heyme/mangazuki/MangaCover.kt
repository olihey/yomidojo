package com.oliver.heyme.mangazuki

/**
 * Dedicated Coil model for series covers. Using a custom type (not a String) bypasses Coil's
 * built-in String→Uri mapper, so the platform CoverFetcher.Factory actually receives it.
 * [model] is the scheme-tagged locator, e.g. "cbz:content://…" or "imgdir:content://…".
 *
 * [seriesId] is set only at the library grid's series-cover call site (PLAN.md §9.4) — when
 * non-null, a live-extracted [model] gets promoted to a permanent `cover_path` after the first
 * fetch, the same way a matched series' downloaded cover is. Left null everywhere else (chapter
 * covers, banners, the reader's next-chapter preview), which are never persisted this way.
 *
 * [cacheKey] is what `Keyer<MangaCover>` actually keys Coil's memory/disk cache on — defaults to
 * [model], but the library grid passes a key that's stable across the "just persisted the
 * live-extracted cover" transition (§9.4: same bytes, [model] changes from a scheme-tagged
 * locator to a real file path) so that doesn't read as a new image and flicker-reload the tile,
 * while still changing once the series is actually matched (a genuinely different, downloaded
 * cover) so *that* transition correctly reloads.
 */
data class MangaCover(val model: String, val seriesId: String? = null, val cacheKey: String = model)

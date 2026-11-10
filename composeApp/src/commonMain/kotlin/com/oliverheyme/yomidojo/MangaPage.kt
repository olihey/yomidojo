package com.oliverheyme.yomidojo

import kotlin.math.ceil

/**
 * Coil model for one reader page. `model` is the chapter's scheme-tagged locator
 * ("cbz:<uri>" / "imgdir:<uri>" / "pdf:<uri>", same scheme as [MangaCover]); `index` selects
 * the page within it. A dedicated data class — not a String — so Coil's built-in String→Uri
 * mapper doesn't intercept it before the custom fetcher runs (see MangaCover).
 *
 * `size` is the chapter file's size (`ChapterCard.size`) — lets `PageFetcher` use a range
 * read instead of downloading the whole CBZ over a network source (PLAN.md §6.2), and serves
 * as the PDF materialization cache's validity check (PLAN.md §16); null is always safe.
 *
 * `slice`/`sliceCount` select one horizontal band of a very tall page (PLAN.md §8.2) — see
 * [webtoonSliceCount]. `sliceCount == 1` means the whole page.
 */
data class MangaPage(
    val model: String,
    val index: Int,
    val size: Long? = null,
    val slice: Int = 0,
    val sliceCount: Int = 1,
)

/** Pages up to this tall decode as one bitmap. GPUs cap texture height (8192 px on some
 * devices, 16384 on most), and a taller hardware bitmap silently fails to allocate. */
const val MAX_UNSLICED_PAGE_PX = 8192

/** Target height of each band when a page is sliced. */
const val PAGE_SLICE_PX = 4096

/** How many bands the webtoon reader draws a page of [heightPx] pixels as (PLAN.md §8.2). */
fun webtoonSliceCount(heightPx: Float): Int =
    if (heightPx <= MAX_UNSLICED_PAGE_PX) 1 else ceil(heightPx / PAGE_SLICE_PX).toInt()

package com.oliver.heyme.mangazuki.core.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import com.oliver.heyme.mangazuki.core.domain.Chapter
import com.oliver.heyme.mangazuki.core.source.MangaSource

/**
 * The reader's only seam (PLAN.md §8). The viewer knows nothing about format or
 * source — just "page N of a chapter". Adding PDF is one new implementation here.
 *
 * `pageSize` is first-class: double-page-spread detection uses the aspect-ratio
 * heuristic (wide page = pre-stitched spread, shown alone; portrait pages pair
 * two-up on landscape), which needs dimensions BEFORE rendering (PLAN.md §8).
 */
interface PageProvider {
    val pageCount: Int
    suspend fun loadPage(index: Int, target: PageTarget): ImageBitmap
    suspend fun pageSize(index: Int): Size
    fun close()
}

/** Decode target — drives downsampling (PLAN.md §8 memory strategy). */
data class PageTarget(val maxWidthPx: Int, val maxHeightPx: Int)

// class PdfPageProvider(...) : PageProvider // deferred (§16) — the ONLY new file PDF needs

/** Platform actual picks ImageDirPageProvider / CbzPageProvider by chapter.format. Suspends
 * because resolving the page list is real I/O — never blocks the calling thread (PLAN.md §8). */
expect suspend fun pageProviderFor(chapter: Chapter, source: MangaSource): PageProvider

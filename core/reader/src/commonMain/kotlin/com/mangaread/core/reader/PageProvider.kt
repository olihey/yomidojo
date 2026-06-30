package com.mangaread.core.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import com.mangaread.core.domain.Chapter
import com.mangaread.core.domain.ChapterFormat

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

// Implementations land in Phase 2:
// class ImageDirPageProvider(...) : PageProvider
// class CbzPageProvider(...)      : PageProvider     // local-temp/range shim for non-RANDOM_ACCESS cloud (§11)
// class PdfPageProvider(...)      : PageProvider     // deferred (§16) — the ONLY new file PDF needs

fun pageProviderFor(chapter: Chapter): PageProvider = when (chapter.format) {
    ChapterFormat.IMAGE_DIR -> TODO("ImageDirPageProvider — Phase 2")
    ChapterFormat.CBZ -> TODO("CbzPageProvider — Phase 2")
}

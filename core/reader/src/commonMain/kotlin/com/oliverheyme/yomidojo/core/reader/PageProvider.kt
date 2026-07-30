package com.oliverheyme.yomidojo.core.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import com.oliverheyme.yomidojo.core.domain.Chapter
import com.oliverheyme.yomidojo.core.domain.ChapterFormat
import com.oliverheyme.yomidojo.core.source.MangaSource

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

/** Platform actual picks ImageDirPageProvider / CbzPageProvider / PdfPageProvider by
 * chapter.format. Suspends because resolving the page list is real I/O — never blocks the
 * calling thread (PLAN.md §8).
 *
 * The PDF-only parameters (PLAN.md §16): [pdfCacheDir] is where a PDF chapter is materialized
 * to a seekable local file before Pdfium opens it — required for [ChapterFormat.PDF], ignored
 * for the other formats. [onPdfMaterializeProgress] reports (bytesCopied, totalBytes — null
 * means indeterminate) during that copy, which is download-sized on remote sources; it never
 * fires on a cache hit or for non-PDF chapters. */
expect suspend fun pageProviderFor(
    chapter: Chapter,
    source: MangaSource,
    pdfCacheDir: String? = null,
    onPdfMaterializeProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit = { _, _ -> },
): PageProvider

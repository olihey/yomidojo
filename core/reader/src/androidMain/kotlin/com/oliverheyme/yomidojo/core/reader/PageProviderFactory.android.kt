package com.oliverheyme.yomidojo.core.reader

import com.oliverheyme.yomidojo.core.domain.Chapter
import com.oliverheyme.yomidojo.core.domain.ChapterFormat
import com.oliverheyme.yomidojo.core.source.MangaSource

actual suspend fun pageProviderFor(
    chapter: Chapter,
    source: MangaSource,
    pdfCacheDir: String?,
    onPdfMaterializeProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit,
): PageProvider = when (chapter.format) {
    ChapterFormat.IMAGE_DIR -> ImageDirPageProvider.create(chapter.locator, source)
    ChapterFormat.CBZ -> CbzPageProvider.create(chapter.locator, source, chapter.size)
    ChapterFormat.PDF -> PdfPageProvider.create(
        chapter.locator, source, chapter.size,
        pdfCacheDir ?: error("PDF chapters need a pdfCacheDir (PLAN.md §16)"),
        onPdfMaterializeProgress,
    )
}

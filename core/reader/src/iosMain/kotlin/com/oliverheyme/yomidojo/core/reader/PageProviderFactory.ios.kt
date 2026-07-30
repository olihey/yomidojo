package com.oliverheyme.yomidojo.core.reader

import com.oliverheyme.yomidojo.core.domain.Chapter
import com.oliverheyme.yomidojo.core.source.MangaSource

actual suspend fun pageProviderFor(
    chapter: Chapter,
    source: MangaSource,
    pdfCacheDir: String?,
    onPdfMaterializeProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit,
): PageProvider =
    TODO("iOS PageProvider — pending Mac bring-up (PLAN.md §12); PDF via PDFKit or Pdfium-cinterop (§16)")

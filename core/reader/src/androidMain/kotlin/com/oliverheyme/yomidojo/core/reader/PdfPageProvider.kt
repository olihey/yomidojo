package com.oliverheyme.yomidojo.core.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.oliverheyme.yomidojo.core.domain.ioDispatcher
import com.oliverheyme.yomidojo.core.source.MangaSource
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt

/**
 * [PageProvider] over a Pdfium document (PLAN.md §16). Unlike CBZ/image-dir chapters there are
 * no encoded image bytes to hand Coil -- Pdfium rasterizes straight into a Bitmap -- so on-screen
 * rendering ALSO goes through Pdfium (composeApp's `PageFetcher`, `pdf:` scheme) rather than a
 * plain byte stream; this provider covers the same probing duties as its CBZ sibling (page
 * count, per-page aspect ratio for spread pairing) plus [loadPage] for any direct use.
 *
 * The document is opened from a locally materialized copy ([PdfFileCache]) because Pdfium needs
 * a seekable fd. Password-protected PDFs fail here with Pdfium's own `PdfPasswordException` --
 * deliberately unsupported (PLAN.md §16).
 */
class PdfPageProvider private constructor(
    private val document: PdfDocumentKt,
    private val fd: ParcelFileDescriptor,
    override val pageCount: Int,
) : PageProvider {

    override suspend fun loadPage(index: Int, target: PageTarget): ImageBitmap {
        return document.openPage(index).use { page ->
            val widthPt = page.getPageWidthPoint()
            val heightPt = page.getPageHeightPoint()
            // Fit the page into the decode target preserving aspect ratio -- same downsampling
            // intent as decodeSampled for image chapters (PLAN.md §8 memory strategy), just
            // applied before rasterizing instead of after.
            val scale = minOf(
                target.maxWidthPx / widthPt.toFloat(),
                target.maxHeightPx / heightPt.toFloat(),
            ).coerceAtMost(PDF_MAX_RENDER_SCALE)
            val w = (widthPt * scale).toInt().coerceAtLeast(1)
            val h = (heightPt * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            page.renderPageBitmap(bitmap, 0, 0, w, h, canvasColor = Color.WHITE, pageBackgroundColor = Color.WHITE)
            bitmap.asImageBitmap()
        }
    }

    override suspend fun pageSize(index: Int): Size = document.openPage(index).use { page ->
        Size(page.getPageWidthPoint().toFloat(), page.getPageHeightPoint().toFloat())
    }

    override fun close() {
        document.close()
        runCatching { fd.close() }
    }

    companion object {
        /** PDF points are 1/72", so a page's point size is far below screen resolution -- but an
         * unbounded scale on a huge decode target could still balloon a spread page; cap it. */
        private const val PDF_MAX_RENDER_SCALE = 4f

        /** [fileSize] is [com.oliverheyme.yomidojo.core.domain.Chapter.size] as recorded at
         * scan time (same convention as [CbzPageProvider.create]) — used both as the cache
         * validity check and the progress denominator. */
        suspend fun create(
            pdfLocator: String,
            source: MangaSource,
            fileSize: Long?,
            pdfCacheDir: String,
            onProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        ): PdfPageProvider {
            val file = PdfFileCache.materialize(pdfLocator, source, fileSize, pdfCacheDir, onProgress)
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val document = PdfiumCoreKt(ioDispatcher).newDocument(fd)
            return PdfPageProvider(document, fd, document.getPageCount())
        }
    }
}

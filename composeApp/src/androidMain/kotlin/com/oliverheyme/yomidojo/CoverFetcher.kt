package com.oliverheyme.yomidojo

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.oliverheyme.yomidojo.core.data.LibraryRepository
import com.oliverheyme.yomidojo.core.reader.PageTarget
import com.oliverheyme.yomidojo.core.reader.PdfPageProvider
import com.oliverheyme.yomidojo.core.source.MangaSource
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Resolves a cover model into image bytes (PLAN.md §9 "first page as cover"):
 *   "cbz:<locator>"    → first image entry inside the archive (not yet cached)
 *   "imgdir:<locator>" → first image file in the folder (not yet cached)
 *   "pdf:<locator>"    → page 0 rendered by Pdfium, JPEG-encoded (not yet cached) — encoding
 *                        (unlike reader pages, PLAN.md §16) keeps a PDF cover on the exact same
 *                        bytes path as the others: Coil disk cache + series-cover persistence.
 *                        Note this materializes the whole PDF locally first — a one-time full
 *                        download on remote sources, then reused when the chapter is opened.
 *   anything else      → a cached app-internal file path (already-generated chapter/series cover)
 * Coil caches the result by the model string, so each cover is extracted once per app lifetime.
 * Reads go through [MangaSource] (not a hardcoded Android `ContentResolver`) so this works for
 * any source implementation (SAF, SMB, ...) — see PLAN.md §6.
 *
 * When [seriesId] is set (library-grid series covers only, PLAN.md §9.4), a live-extracted cover
 * is additionally written to app-internal storage and promoted to `series.cover_path` — the same
 * persistent cache a matched series' downloaded cover gets — so an unmatched series' cover
 * survives Coil's disk cache being cleared instead of re-extracting (re-downloading, for SMB)
 * from the source every time.
 */
class CoverFetcher(
    private val data: String,
    private val seriesId: String?,
    private val source: MangaSource,
    private val repository: LibraryRepository,
    private val coversDir: String,
    private val pdfCacheDir: String,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val locator = data.substringAfter(':')
        try {
            val bytes: ByteArray? = when {
                data.startsWith("cbz:") -> firstCbzImage(locator)
                data.startsWith("imgdir:") -> firstFolderImage(locator)
                data.startsWith("pdf:") -> firstPdfPageJpeg(locator)
                else -> null
            }
            val bufferedSource = if (bytes != null) {
                persistIfNeeded(bytes)
                Buffer().apply { write(bytes) }
            } else {
                FileSystem.SYSTEM.source(File(data).toOkioPath()).buffer()
            }
            return SourceFetchResult(
                source = ImageSource(bufferedSource, FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (t: Throwable) {
            android.util.Log.e("CoverFetcher", "FAIL $data : ${t.message}", t)
            throw t
        }
    }

    /** Only fires for a series' own cover ([seriesId] non-null) — chapter covers, banners, and
     * the reader's next-chapter preview leave it null and are never persisted this way. Guarded
     * DB-side (`cover_path IS NULL`), so this can't race a real match's downloaded cover. */
    private suspend fun persistIfNeeded(bytes: ByteArray) {
        if (seriesId == null) return
        val path = writeImageBytes(coversDir, "$seriesId.jpg", bytes)
        repository.cacheSeriesCoverIfMissing(seriesId, path)
    }

    private suspend fun firstFolderImage(dirLocator: String): ByteArray {
        val first = source.list(dirLocator)
            .filter { !it.isDirectory && it.name.isImageName() }
            .minByOrNull { it.name }
            ?: error("no images in $dirLocator")
        return source.open(first.locator).buffer().readByteArray()
    }

    private suspend fun firstCbzImage(cbzLocator: String): ByteArray {
        ZipInputStream(source.open(cbzLocator).buffer().inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.isImageName()) return zis.readBytes()
                entry = zis.nextEntry
            }
        }
        error("no image entry in $cbzLocator")
    }

    /** Page 0 rendered at cover resolution and JPEG-encoded (see class KDoc). The provider is
     * opened and closed per fetch — covers resolve once and then live in Coil's disk cache, so
     * a longer-lived document handle (the reader's `PdfProviderCache`) isn't worth holding. */
    private suspend fun firstPdfPageJpeg(pdfLocator: String): ByteArray {
        val provider = PdfPageProvider.create(pdfLocator, source, fileSize = null, pdfCacheDir = pdfCacheDir)
        try {
            val bitmap = provider.loadPage(0, PageTarget(COVER_RENDER_W_PX, COVER_RENDER_H_PX)).asAndroidBitmap()
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } finally {
            provider.close()
        }
    }

    class Factory(
        private val source: MangaSource,
        private val repository: LibraryRepository,
        private val coversDir: String,
        private val pdfCacheDir: String,
    ) : Fetcher.Factory<MangaCover> {
        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(data.model, data.seriesId, source, repository, coversDir, pdfCacheDir)
    }

    private companion object {
        // Grid tiles and the series header never show a cover larger than this; matches the
        // rough quality of a CBZ's extracted first page without rasterizing print-resolution.
        const val COVER_RENDER_W_PX = 600
        const val COVER_RENDER_H_PX = 900
    }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

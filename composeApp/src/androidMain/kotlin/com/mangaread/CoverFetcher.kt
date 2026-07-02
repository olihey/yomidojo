package com.mangaread

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.source.MangaSource
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import okio.buffer
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Resolves a cover model into image bytes (PLAN.md §9 "first page as cover"):
 *   "cbz:<locator>"    → first image entry inside the archive (not yet cached)
 *   "imgdir:<locator>" → first image file in the folder (not yet cached)
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
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val locator = data.substringAfter(':')
        try {
            val bytes: ByteArray? = when {
                data.startsWith("cbz:") -> firstCbzImage(locator)
                data.startsWith("imgdir:") -> firstFolderImage(locator)
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

    class Factory(
        private val source: MangaSource,
        private val repository: LibraryRepository,
        private val coversDir: String,
    ) : Fetcher.Factory<MangaCover> {
        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(data.model, data.seriesId, source, repository, coversDir)
    }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

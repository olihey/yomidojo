package com.mangaread

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.mangaread.core.source.MangaSource
import okio.Buffer
import okio.BufferedSource
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
 * Coil caches the result by the model string, so each cover is extracted once. Reads go
 * through [MangaSource] (not a hardcoded Android `ContentResolver`) so this works for any
 * source implementation (SAF, SMB, ...) — see PLAN.md §6.
 */
class CoverFetcher(
    private val data: String,
    private val source: MangaSource,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val locator = data.substringAfter(':')
        try {
            val bytes: BufferedSource = when {
                data.startsWith("cbz:") -> firstCbzImage(locator)
                data.startsWith("imgdir:") -> firstFolderImage(locator)
                else -> FileSystem.SYSTEM.source(File(data).toOkioPath()).buffer()
            }
            return SourceFetchResult(
                source = ImageSource(bytes, FileSystem.SYSTEM),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        } catch (t: Throwable) {
            android.util.Log.e("CoverFetcher", "FAIL $data : ${t.message}", t)
            throw t
        }
    }

    private suspend fun firstFolderImage(dirLocator: String): BufferedSource {
        val first = source.list(dirLocator)
            .filter { !it.isDirectory && it.name.isImageName() }
            .minByOrNull { it.name }
            ?: error("no images in $dirLocator")
        return source.open(first.locator).buffer()
    }

    private suspend fun firstCbzImage(cbzLocator: String): BufferedSource {
        ZipInputStream(source.open(cbzLocator).buffer().inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.isImageName()) {
                    return Buffer().apply { write(zis.readBytes()) }
                }
                entry = zis.nextEntry
            }
        }
        error("no image entry in $cbzLocator")
    }

    class Factory(private val source: MangaSource) : Fetcher.Factory<MangaCover> {
        override fun create(data: MangaCover, options: Options, imageLoader: ImageLoader): Fetcher =
            CoverFetcher(data.model, source)
    }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

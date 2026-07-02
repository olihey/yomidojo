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
import okio.buffer
import java.util.zip.ZipInputStream

/** Resolves one page of a chapter (image dir or CBZ) into image bytes for the reader
 * pager. Reads go through [MangaSource] (not a hardcoded Android `ContentResolver`) so
 * this works for any source implementation (SAF, SMB, ...) — see PLAN.md §6. */
class PageFetcher(
    private val page: MangaPage,
    private val source: MangaSource,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val locator = page.model.substringAfter(':')
        val bytes: BufferedSource = when {
            page.model.startsWith("cbz:") -> cbzImageAt(locator, page.index)
            page.model.startsWith("imgdir:") -> folderImageAt(locator, page.index)
            else -> error("unsupported page model: ${page.model}")
        }
        return SourceFetchResult(
            source = ImageSource(bytes, FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun folderImageAt(dirLocator: String, index: Int): BufferedSource {
        val entry = source.list(dirLocator)
            .filter { !it.isDirectory && it.name.isImageName() }
            .sortedBy { it.name }
            .getOrNull(index)
            ?: error("no page $index in $dirLocator")
        return source.open(entry.locator).buffer()
    }

    private suspend fun cbzImageAt(cbzLocator: String, index: Int): BufferedSource {
        val names = mutableListOf<String>()
        ZipInputStream(source.open(cbzLocator).buffer().inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.isImageName()) names += entry.name
                entry = zis.nextEntry
            }
        }
        val target = names.sorted().getOrNull(index) ?: error("no page $index in $cbzLocator")

        ZipInputStream(source.open(cbzLocator).buffer().inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == target) return Buffer().apply { write(zis.readBytes()) }
                entry = zis.nextEntry
            }
        }
        error("no page $index in $cbzLocator")
    }

    class Factory(private val source: MangaSource) : Fetcher.Factory<MangaPage> {
        override fun create(data: MangaPage, options: Options, imageLoader: ImageLoader): Fetcher =
            PageFetcher(data, source)
    }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

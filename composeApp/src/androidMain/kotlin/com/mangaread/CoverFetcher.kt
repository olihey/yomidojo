package com.mangaread

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.buffer
import okio.source
import java.util.zip.ZipInputStream

/**
 * Resolves a scheme-tagged cover model into image bytes (PLAN.md §9 "first page as cover"):
 *   "cbz:<uri>"    → first image entry inside the archive
 *   "imgdir:<uri>" → first image file in the folder
 * Coil caches the result by the model string, so each cover is extracted once.
 */
class CoverFetcher(
    private val data: String,
    private val context: Context,
    private val source: SafMangaSource,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val locator = data.substringAfter(':')
        val bytes: BufferedSource = when {
            data.startsWith("cbz:") -> firstCbzImage(locator)
            data.startsWith("imgdir:") -> firstFolderImage(locator)
            else -> error("unsupported cover model: $data")
        }
        return SourceFetchResult(
            source = ImageSource(bytes, FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    private suspend fun firstFolderImage(dirLocator: String): BufferedSource {
        val first = source.list(dirLocator)
            .filter { !it.isDirectory && it.name.isImageName() }
            .minByOrNull { it.name }
            ?: error("no images in $dirLocator")
        val stream = context.contentResolver.openInputStream(Uri.parse(first.locator))
            ?: error("cannot open ${first.locator}")
        return stream.source().buffer()
    }

    private fun firstCbzImage(cbzLocator: String): BufferedSource {
        val input = context.contentResolver.openInputStream(Uri.parse(cbzLocator))
            ?: error("cannot open $cbzLocator")
        ZipInputStream(input.buffered()).use { zis ->
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

    class Factory(
        private val context: Context,
        private val source: SafMangaSource,
    ) : Fetcher.Factory<String> {
        override fun create(data: String, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (data.startsWith("cbz:") || data.startsWith("imgdir:")) {
                CoverFetcher(data, context, source)
            } else {
                null // let Coil handle real URLs/paths (e.g. a cached cover file)
            }
    }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

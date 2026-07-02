package com.mangaread

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.mangaread.core.reader.CbzArchive
import com.mangaread.core.source.MangaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.buffer

/** Resolves one page of a chapter (image dir or CBZ) into image bytes for the reader
 * pager. Reads go through [MangaSource] (not a hardcoded Android `ContentResolver`) so
 * this works for any source implementation (SAF, SMB, ...) — see PLAN.md §6. CBZ pages go
 * through [CbzArchive] (same range-read logic `CbzPageProvider` uses for aspect-ratio
 * probing) rather than re-scanning the whole archive per page — critical over SMB (§6.2). */
class PageFetcher(
    private val page: MangaPage,
    private val source: MangaSource,
    private val archives: CbzArchiveCache,
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

    private suspend fun cbzImageAt(cbzLocator: String, index: Int): BufferedSource =
        Buffer().apply { write(archives.readPage(cbzLocator, page.size, source, index)) }

    class Factory(private val source: MangaSource) : Fetcher.Factory<MangaPage> {
        private val archives = CbzArchiveCache()
        override fun create(data: MangaPage, options: Options, imageLoader: ImageLoader): Fetcher =
            PageFetcher(data, source, archives)
    }
}

/**
 * Keeps a couple of [CbzArchive]s open across page fetches (one `Factory` per [MangaSource],
 * long-lived for the app's lifetime — PLAN.md §6.2), so turning pages within a chapter doesn't
 * pay a fresh central-directory parse — and for SMB, a fresh file handle open/close — on every
 * single page. [maxOpen] of 2 covers "reading forward in the current chapter" plus a prefetch
 * that's already crossed into the next one.
 *
 * All access is serialized through one [Mutex] rather than per-archive: this is a
 * latency-bound workload already (network round-trips dominate any lock contention), and
 * smbj's thread-safety for concurrent reads on one shared file handle isn't something this
 * app relies on — safer to queue than to risk it, especially now that the pager prefetches
 * the next page (`ReaderScreen`'s `beyondViewportPageCount`), which is exactly the scenario
 * that would otherwise race two reads against the same handle.
 */
class CbzArchiveCache(private val maxOpen: Int = 2) {
    private val mutex = Mutex()
    private val open = LinkedHashMap<String, CbzArchive>() // iteration order = LRU

    suspend fun readPage(locator: String, fileSize: Long?, source: MangaSource, index: Int): ByteArray =
        mutex.withLock {
            val archive = open.remove(locator) ?: CbzArchive.open(locator, source, fileSize)
            open[locator] = archive // re-insert: now most-recently-used
            while (open.size > maxOpen) {
                val oldest = open.entries.iterator()
                val (oldestKey, oldestArchive) = oldest.next()
                oldest.remove()
                oldestArchive.close()
            }
            archive.readPage(index)
        }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

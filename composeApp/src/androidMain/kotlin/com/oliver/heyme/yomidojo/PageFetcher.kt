package com.oliver.heyme.yomidojo

import androidx.compose.ui.graphics.asAndroidBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import com.oliver.heyme.yomidojo.core.reader.CbzArchive
import com.oliver.heyme.yomidojo.core.reader.PageTarget
import com.oliver.heyme.yomidojo.core.reader.PdfPageProvider
import com.oliver.heyme.yomidojo.core.source.MangaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.buffer

/** Resolves one page of a chapter (image dir, CBZ, or PDF) into a Coil result for the reader
 * pager. Reads go through [MangaSource] (not a hardcoded Android `ContentResolver`) so
 * this works for any source implementation (SAF, SMB, ...) — see PLAN.md §6. CBZ pages go
 * through [CbzArchive] (same range-read logic `CbzPageProvider` uses for aspect-ratio
 * probing) rather than re-scanning the whole archive per page — critical over SMB (§6.2).
 * PDF pages are the odd one out (PLAN.md §16): Pdfium rasterizes straight into a Bitmap, so
 * there are no encoded bytes to hand Coil — they return an [ImageFetchResult] (memory-cached
 * only; re-encoding just to reach Coil's disk cache is a deferred optimization). */
class PageFetcher(
    private val page: MangaPage,
    private val source: MangaSource,
    private val options: Options,
    private val archives: CbzArchiveCache,
    private val pdfProviders: PdfProviderCache,
    private val pdfCacheDir: String,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val locator = page.model.substringAfter(':')
        if (page.model.startsWith("pdf:")) return pdfPageAt(locator, page.index)
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

    private suspend fun pdfPageAt(pdfLocator: String, index: Int): FetchResult {
        // Coil's request size is the composable's measured size — the same downsampling driver
        // decodeSampled uses for image pages, applied here as the Pdfium raster target.
        val target = PageTarget(
            maxWidthPx = options.size.width.pxOrElse { DEFAULT_PDF_RENDER_PX },
            maxHeightPx = options.size.height.pxOrElse { DEFAULT_PDF_RENDER_PX },
        )
        val bitmap = pdfProviders.loadPage(pdfLocator, page.size, source, pdfCacheDir, index, target)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(
        private val source: MangaSource,
        private val pdfCacheDir: String,
    ) : Fetcher.Factory<MangaPage> {
        private val archives = CbzArchiveCache()
        private val pdfProviders = PdfProviderCache()
        override fun create(data: MangaPage, options: Options, imageLoader: ImageLoader): Fetcher =
            PageFetcher(data, source, options, archives, pdfProviders, pdfCacheDir)
    }

    private companion object {
        /** Raster target when Coil's request size is undefined (e.g. prefetch without a
         * measured composable) — roughly a tablet's portrait height, matching the quality the
         * on-screen request would ask for anyway. */
        const val DEFAULT_PDF_RENDER_PX = 2048
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

/**
 * [CbzArchiveCache]'s PDF sibling (PLAN.md §16): keeps a couple of Pdfium documents open across
 * page fetches so paging within a chapter doesn't re-open (and on a cold cache, re-download)
 * the document per page. Same single-Mutex serialization — Pdfium isn't thread-safe, and the
 * pager's prefetch would otherwise race two renders against one document.
 */
class PdfProviderCache(private val maxOpen: Int = 2) {
    private val mutex = Mutex()
    private val open = LinkedHashMap<String, PdfPageProvider>() // iteration order = LRU

    suspend fun loadPage(
        locator: String,
        fileSize: Long?,
        source: MangaSource,
        pdfCacheDir: String,
        index: Int,
        target: PageTarget,
    ): android.graphics.Bitmap = mutex.withLock {
        val provider = open.remove(locator) ?: PdfPageProvider.create(locator, source, fileSize, pdfCacheDir)
        open[locator] = provider // re-insert: now most-recently-used
        while (open.size > maxOpen) {
            val oldest = open.entries.iterator()
            val oldestProvider = oldest.next().value
            oldest.remove()
            oldestProvider.close()
        }
        provider.loadPage(index, target).asAndroidBitmap()
    }
}

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

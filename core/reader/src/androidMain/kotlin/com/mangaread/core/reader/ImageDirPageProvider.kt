package com.mangaread.core.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mangaread.core.domain.ioDispatcher
import com.mangaread.core.source.MangaSource
import com.mangaread.core.source.SourceEntry
import kotlinx.coroutines.withContext
import okio.buffer

private fun String.isImageName(): Boolean =
    substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

/**
 * One chapter = a folder of naturally-sorted images (PLAN.md §11). The page list is resolved by
 * [create] (suspend) rather than the constructor, so building a provider never blocks a calling
 * thread — a plain blocking constructor here previously froze the caller (e.g. the series screen
 * counting pages for many chapters at once) since directory listing is real I/O.
 */
class ImageDirPageProvider private constructor(
    private val pages: List<SourceEntry>,
    private val source: MangaSource,
) : PageProvider {

    override val pageCount: Int get() = pages.size

    // Explicit dispatch, not just suspend — MangaSource.open()/list() are non-blocking to call,
    // but reading from the returned Source (or listing a directory) can be genuine socket I/O
    // (SMB, PLAN.md §6), which StrictMode kills outright with NetworkOnMainThreadException if
    // it happens on the caller's thread (SAF's Binder-based reads never tripped this, so the
    // gap stayed latent until a real network source existed).
    override suspend fun loadPage(index: Int, target: PageTarget): ImageBitmap = withContext(ioDispatcher) {
        val bytes = source.open(pages[index].locator).buffer().use { it.readByteArray() }
        decodeSampled(bytes, target.maxWidthPx, target.maxHeightPx).asImageBitmap()
    }

    override suspend fun pageSize(index: Int): Size = withContext(ioDispatcher) {
        val bytes = source.open(pages[index].locator).buffer().use { it.readByteArray() }
        decodeBoundsSize(bytes)
    }

    override fun close() {}

    companion object {
        suspend fun create(dirLocator: String, source: MangaSource): ImageDirPageProvider = withContext(ioDispatcher) {
            val pages = source.list(dirLocator).filter { !it.isDirectory && it.name.isImageName() }.sortedBy { it.name }
            ImageDirPageProvider(pages, source)
        }
    }
}

package com.mangaread.core.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.mangaread.core.source.MangaSource

/** Thin [PageProvider] wrapper over [CbzArchive] — page count and per-page aspect-ratio
 * probing for the reader pager (PLAN.md §8, §11). Actual on-screen page rendering goes
 * through Coil (`PageFetcher`, composeApp), which uses [CbzArchive] directly. */
class CbzPageProvider private constructor(private val archive: CbzArchive) : PageProvider {

    override val pageCount: Int get() = archive.pageCount

    override suspend fun loadPage(index: Int, target: PageTarget): ImageBitmap =
        decodeSampled(archive.readPage(index), target.maxWidthPx, target.maxHeightPx).asImageBitmap()

    override suspend fun pageSize(index: Int): Size =
        archive.knownPageSize(index) ?: decodeBoundsSize(archive.readPage(index))

    override fun close() = archive.close()

    companion object {
        /** [fileSize] is [com.mangaread.core.domain.Chapter.size] as recorded at scan time. */
        suspend fun create(cbzLocator: String, source: MangaSource, fileSize: Long?): CbzPageProvider =
            CbzPageProvider(CbzArchive.open(cbzLocator, source, fileSize))
    }
}

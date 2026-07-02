package com.mangaread.core.reader

import com.mangaread.core.domain.Chapter
import com.mangaread.core.domain.ChapterFormat
import com.mangaread.core.source.MangaSource

actual suspend fun pageProviderFor(chapter: Chapter, source: MangaSource): PageProvider = when (chapter.format) {
    ChapterFormat.IMAGE_DIR -> ImageDirPageProvider.create(chapter.locator, source)
    ChapterFormat.CBZ -> CbzPageProvider.create(chapter.locator, source, chapter.size)
}

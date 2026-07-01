package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.source.MangaSource

/** The small set of shared singletons screens below the library need (PLAN.md §4). */
class AppGraph(
    val repository: LibraryRepository,
    val source: MangaSource,
    val libraryViewModel: LibraryViewModel,
    val readerPreferences: ReaderPreferences,
    val appPreferences: AppPreferences,
)

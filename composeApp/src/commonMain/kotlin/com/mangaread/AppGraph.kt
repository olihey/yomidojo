package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.metadata.MetadataProvider
import com.mangaread.core.source.MangaSource
import io.ktor.client.HttpClient

/** The small set of shared singletons screens below the library need (PLAN.md §4). */
class AppGraph(
    val repository: LibraryRepository,
    val source: MangaSource,
    val libraryViewModel: LibraryViewModel,
    val readerPreferences: ReaderPreferences,
    val appPreferences: AppPreferences,
    val metadataProvider: MetadataProvider,
    val metadataEnricher: MetadataEnricher,
    /** Shared with [MetadataEnricher] — Fix Metadata (§9.1) downloads covers the same way. */
    val coverClient: HttpClient,
    val coversDir: String,
)

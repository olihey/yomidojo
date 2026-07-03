package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.source.MangaSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.StateFlow

/** The small set of shared singletons screens below the library need (PLAN.md §4). */
class AppGraph(
    val repository: LibraryRepository,
    val source: MangaSource,
    val libraryViewModel: LibraryViewModel,
    val readerPreferences: ReaderPreferences,
    val appPreferences: AppPreferences,
    val metadataProviders: MetadataProviders,
    val metadataEnricher: MetadataEnricher,
    /** Shared with [MetadataEnricher] — Fix Metadata (§9.1) downloads covers the same way. */
    val coverClient: HttpClient,
    val coversDir: String,
    /** Google Drive sign-in status (PLAN.md §10) — the actual sign-in/sign-out actions are
     * plain callbacks passed into [App], not held here, since triggering them needs an Android
     * `Activity`/`Intent` (the same reason `onPickFolder` is a callback, not a stored field). */
    val syncState: StateFlow<SyncState>,
)

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
    /** Debounced trigger (PLAN.md §10) called by view models after a progress-mutating write, so
     * read status converges across devices without waiting for the next 6-hourly [SyncWorker]
     * run. Backed by a [ProgressSyncScheduler] built in `MainActivity` -- a plain callback, not a
     * stored dependency, for the same reason [syncState]'s own actions are callbacks. */
    val requestSync: () -> Unit = {},
    /** Settings' "Sync in background" sub-toggle (PLAN.md §10) -- persists
     * [AppPreferences.setBackgroundSyncEnabled] *and* (de)schedules the actual WorkManager job,
     * since a merely-persisted flag wouldn't stop the OS from waking the process up on schedule.
     * A callback for the same Android-only reason [syncState]'s sign-in actions are. */
    val onBackgroundSyncEnabledChanged: (Boolean) -> Unit = {},
)

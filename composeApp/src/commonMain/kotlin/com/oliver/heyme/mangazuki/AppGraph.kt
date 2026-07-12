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
    /** Where PDF chapters are materialized to a local seekable file before Pdfium opens them
     * (PLAN.md §16) — under the platform cache dir, so the OS may reclaim it under pressure. */
    val pdfCacheDir: String,
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
    /** Settings' Debug section (PLAN.md §10) -- view/clear actions on the raw
     * `progress.json`/`metadata_aliases.json` files on Drive, since `appDataFolder` can't be
     * browsed any other way. Fetches return null if signed out. Callbacks for the same
     * Android-only reason [syncState]'s sign-in actions are. */
    val fetchProgressJson: suspend () -> String? = { null },
    val fetchMetadataAliasesJson: suspend () -> String? = { null },
    val fetchFavoritesJson: suspend () -> String? = { null },
    val clearProgressJson: suspend () -> Unit = {},
    val clearMetadataAliasesJson: suspend () -> Unit = {},
    val clearFavoritesJson: suspend () -> Unit = {},
    /** Settings' Debug section "Export"/"Import" actions (PLAN.md §10) -- both files share the
     * same SAF plumbing (one file-save picker, one file-open-and-read picker), so a single pair
     * of generic callbacks covers both rather than four near-identical ones. [exportJsonFile]
     * writes `content` to a user-picked location suggested as `fileName`; [pickJsonFile] opens a
     * picker and returns the picked file's text, or `null` if the user backed out. Callbacks for
     * the same Android-only (SAF/`Uri`) reason [fetchProgressJson]'s pair are. */
    val exportJsonFile: suspend (fileName: String, content: String) -> Unit = { _, _ -> },
    val pickJsonFile: suspend () -> String? = { null },
    /** The picked file's raw text is pushed byte-for-byte to Drive (PLAN.md §10) -- see
     * `GoogleDriveSyncBackend.pushRawProgressJson`/`pushRawMetadataAliasesJson`. Throws if the
     * picked file doesn't match the expected wire shape, so Settings can surface the failure
     * rather than silently no-op like [clearProgressJson] does for a mid-flight sign-out. */
    val importProgressJson: suspend (String) -> Unit = {},
    val importMetadataAliasesJson: suspend (String) -> Unit = {},
    val importFavoritesJson: suspend (String) -> Unit = {},
    /** Whether this is a debug build (`BuildConfig.DEBUG`) -- Settings' Debug section (PLAN.md
     * §10) is dev-only, since "clear the Drive file" isn't something a released app should
     * expose. A plain value rather than a callback since it never changes at runtime, but still
     * supplied by `MainActivity` for the same reason [syncState]'s Android-only bits are: nothing
     * in commonMain has its own notion of build type. */
    val isDebugBuild: Boolean = false,
)

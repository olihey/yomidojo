package com.oliver.heyme.mangazuki

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.DatabaseDriverFactory
import com.oliver.heyme.mangazuki.core.data.createMangaDatabase
import com.oliver.heyme.mangazuki.core.metadata.AniListMetadataProvider
import com.oliver.heyme.mangazuki.core.metadata.KitsuMetadataProvider
import com.oliver.heyme.mangazuki.core.scanner.LibraryScanner
import com.oliver.heyme.mangazuki.core.sync.GoogleAuthManager
import com.oliver.heyme.mangazuki.core.sync.GoogleDriveSyncBackend
import com.oliver.heyme.mangazuki.core.sync.NoOpMetadataAliasBackend
import com.russhwolf.settings.SharedPreferencesSettings
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.key.Keyer
import coil3.request.Options
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LibraryViewModel
    private lateinit var pickFolder: ActivityResultLauncher<Uri?>
    private lateinit var readerPrefs: ReaderPreferences
    private lateinit var signIn: ActivityResultLauncher<Intent>
    // Instance properties (not just onCreate locals) so onStart() can reach them for the
    // foreground sync trigger below.
    private lateinit var appPrefs: AppPreferences
    private lateinit var authManager: GoogleAuthManager
    private lateinit var repository: LibraryRepository
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val localSource = SafMangaSource(applicationContext)
        val source = ConfigurableMangaSource(localSource)
        val smbSourceFactory = AndroidSmbSourceFactory(applicationContext)

        val database = createMangaDatabase(DatabaseDriverFactory(applicationContext).create())
        repository = LibraryRepository(database)
        val coversDir = applicationContext.filesDir.resolve("covers").absolutePath

        // Built eagerly (not inside setSafe's lazy factory) so MainActivity can keep a direct
        // reference — Settings -> Reset library needs to clear it, since series/chapter IDs are
        // deterministic (PLAN.md §5) and a later re-scan of the same folder would otherwise
        // resurface an old cached bitmap under the same cache key even after a full DB reset.
        val imageLoader = ImageLoader.Builder(applicationContext)
            .components {
                add(Keyer<MangaCover> { cover, _: Options -> cover.cacheKey })
                add(CoverFetcher.Factory(source, repository, coversDir))
                add(Keyer<MangaPage> { page, _: Options -> "${page.model}#${page.index}" })
                add(PageFetcher.Factory(source))
            }
            // Explicit (rather than relying on Coil's default) so covers/pages extracted
            // on demand from CBZ/folders are only ever extracted once and survive restarts.
            .diskCache {
                DiskCache.Builder()
                    .directory(applicationContext.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizePercent(0.05)
                    .build()
            }
            .build()
        SingletonImageLoader.setSafe { imageLoader }

        val scanner = LibraryScanner(source)
        val prefs = LibraryPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        appPrefs = AppPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        val metadataProviders = MetadataProviders(AniListMetadataProvider(), KitsuMetadataProvider())
        val coverClient = HttpClient()
        val enricher = MetadataEnricher(repository, { metadataProviders.get(appPrefs.metadataProvider.value) }, coverClient, coversDir)

        // Google Drive sync (PLAN.md §10) -- authManager is Android-only (AppAuth), so AppGraph
        // (commonMain) only ever sees the resulting StateFlow, never the manager itself, the
        // same reasoning as the SAF-specific pickFolder launcher below. Built before
        // LibraryViewModel so its requestSync callback can be threaded into the constructor.
        authManager = createGoogleAuthManager(applicationContext)
        val syncState = MutableStateFlow<SyncState>(if (authManager.isSignedIn()) SyncState.SignedIn else SyncState.SignedOut)
        val syncScheduler = ProgressSyncScheduler(activityScope) { runSyncIfEnabled(appPrefs, authManager, repository) }

        viewModel = LibraryViewModel(
            repository, scanner, source, localSource, smbSourceFactory, prefs, enricher, appPrefs, coversDir,
            clearImageCache = { imageLoader.diskCache?.clear(); imageLoader.memoryCache?.clear() },
            requestSync = syncScheduler::requestSync,
        )
        readerPrefs = ReaderPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )

        val graph = AppGraph(
            repository, source, viewModel, readerPrefs, appPrefs,
            metadataProviders, enricher, coverClient, coversDir, syncState,
            requestSync = syncScheduler::requestSync,
            onBackgroundSyncEnabledChanged = { enabled ->
                appPrefs.setBackgroundSyncEnabled(enabled)
                if (enabled) scheduleBackgroundSync() else cancelBackgroundSync()
            },
            fetchProgressJson = {
                if (authManager.isSignedIn()) GoogleDriveSyncBackend(authManager).fetchRawProgressJson() else null
            },
            fetchMetadataAliasesJson = {
                if (authManager.isSignedIn()) GoogleDriveSyncBackend(authManager).fetchRawMetadataAliasesJson() else null
            },
            clearProgressJson = {
                if (authManager.isSignedIn()) GoogleDriveSyncBackend(authManager).clearProgress()
            },
            clearMetadataAliasesJson = {
                if (authManager.isSignedIn()) GoogleDriveSyncBackend(authManager).clearMetadataAliases()
            },
            isDebugBuild = BuildConfig.DEBUG,
        )

        pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                android.util.Log.i("MangaScan", "picked folder: $uri")
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Library"
                viewModel.onFolderPicked(uri.toString(), name)
            }
        }

        signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (data == null) {
                syncState.value = SyncState.SignedOut
                return@registerForActivityResult
            }
            activityScope.launch {
                val outcome = authManager.handleSignInResult(data)
                syncState.value = outcome.fold(
                    onSuccess = { appPrefs.setSyncEnabled(true); SyncState.SignedIn },
                    onFailure = { SyncState.Error(it.message ?: "Sign-in failed") },
                )
                if (outcome.isSuccess) {
                    // Fire-and-forget immediate sync, same "don't make them wait for the next
                    // scheduled background run" pattern as MetadataEnricher's foreground trigger.
                    activityScope.launch { runSyncIfEnabled(appPrefs, authManager, repository) }
                }
            }
        }

        scheduleBackgroundScan()
        if (appPrefs.backgroundSyncEnabled.value) scheduleBackgroundSync()

        enableEdgeToEdge()
        setContent {
            App(
                graph,
                onPickFolder = { pickFolder.launch(null) },
                onSignIn = {
                    // AppAuth throws (not returns an error) building a request with a blank
                    // client id -- guard explicitly rather than crash, since this is the normal
                    // state until GOOGLE_OAUTH_CLIENT_ID is added to local.properties.
                    if (BuildConfig.GOOGLE_OAUTH_CLIENT_ID.isBlank()) {
                        syncState.value = SyncState.Error("Google sync isn't set up yet — see local.properties")
                    } else {
                        syncState.value = SyncState.SigningIn
                        signIn.launch(authManager.signInIntent())
                    }
                },
                onSignOut = { authManager.signOut(); appPrefs.setSyncEnabled(false); syncState.value = SyncState.SignedOut },
            )
        }
    }

    /** Sync the moment the app comes back to the foreground (PLAN.md §10) -- otherwise a device
     * that pushed new progress while this one was backgrounded wouldn't show it here until this
     * device's own next write, sign-in, or the periodic 6h worker happened to run. Also fires
     * once on a cold launch (onStart always follows onCreate) -- harmless, and arguably wanted
     * anyway: opening the app fresh should show whatever's newest, not wait for a write first. */
    override fun onStart() {
        super.onStart()
        activityScope.launch { runSyncIfEnabled(appPrefs, authManager, repository) }
    }

    /** Volume-key paging while the reader is open (PLAN.md §8.1); consumed events skip the
     * system volume UI. [VolumeKeyBus.onVolumeKey] is only set while ReaderScreen is visible. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && readerPrefs.volumeKeyPaging) {
            val down = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> true
                android.view.KeyEvent.KEYCODE_VOLUME_UP -> false
                else -> null
            }
            if (down != null && VolumeKeyBus.onVolumeKey?.invoke(down) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Keep the library fresh in the background (no-ops if no root/grant — see ScanWorker). */
    private fun scheduleBackgroundScan() {
        val request = PeriodicWorkRequestBuilder<ScanWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork("library-scan", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Keep reading progress in sync in the background (PLAN.md §10; no-ops if signed out or
     * sync is disabled -- see SyncWorker). A shorter interval and a network-only constraint
     * (not battery-not-low) than the library scan -- a progress push/pull is small and quick,
     * unlike a full rescan. */
    private fun scheduleBackgroundSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork("progress-sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Settings' "Sync in background" sub-toggle turning off (PLAN.md §10) -- actually cancels
     * the WorkManager registration rather than just no-opping inside [SyncWorker], so the OS
     * stops waking the process up on a schedule at all (the whole point of the toggle). Sync
     * while the app is already open -- sign-in, [ProgressSyncScheduler]'s per-change trigger --
     * is unaffected, since neither depends on this periodic job. */
    private fun cancelBackgroundSync() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork("progress-sync")
    }

    /** Shared by the post-sign-in immediate sync and [ProgressSyncScheduler]'s debounced
     * per-change trigger (PLAN.md §10) -- both need the same signed-in/enabled guard [SyncWorker]
     * already applies for its periodic run, just against this Activity's already-live instances
     * rather than a freshly built throwaway graph. */
    private suspend fun runSyncIfEnabled(appPrefs: AppPreferences, authManager: GoogleAuthManager, repository: LibraryRepository) {
        if (!appPrefs.syncEnabled.value || !authManager.isSignedIn()) return
        // One Drive backend instance covers both files (progress.json, metadata_aliases.json,
        // PLAN.md §10) -- same auth/HTTP plumbing either way.
        val driveBackend = GoogleDriveSyncBackend(authManager)
        // Settings' "Sync fixed metadata" toggle (PLAN.md §10) -- off means never pull/push
        // metadata_aliases.json, without the coordinator needing to know why.
        val aliasBackend = if (appPrefs.metadataAliasSyncEnabled.value) driveBackend else NoOpMetadataAliasBackend
        // Only record an alias "last synced" timestamp when the toggle is actually on -- otherwise
        // the byline would claim a metadata_aliases.json sync that never happened (PLAN.md §10).
        val onAliasSyncCompleted: () -> Unit = { if (appPrefs.metadataAliasSyncEnabled.value) appPrefs.recordMetadataAliasSyncCompleted() }
        runCatching {
            ProgressSyncCoordinator(repository, driveBackend, aliasBackend, appPrefs::recordSyncCompleted, onAliasSyncCompleted).sync()
        }.onFailure { t ->
            // Matches SyncWorker's own logging for the same failure mode -- silent before, which
            // made a partial failure (e.g. the progress half already pushed, then the alias half
            // throwing) indistinguishable from "never ran at all" from Settings' bylines alone.
            android.util.Log.w("MainActivity", "foreground sync failed", t)
        }
    }
}

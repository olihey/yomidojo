package com.oliver.heyme.mangazuki

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.oliver.heyme.mangazuki.core.data.DatabaseDriverFactory
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.createMangaDatabase
import com.oliver.heyme.mangazuki.core.sync.GoogleDriveSyncBackend
import com.oliver.heyme.mangazuki.core.sync.NoOpMetadataAliasBackend
import com.russhwolf.settings.SharedPreferencesSettings

/**
 * Background reading-progress sync (PLAN.md §10, §15) so progress converges across devices
 * without the app needing to be open. Builds its own object graph from the app context, same
 * pattern as [ScanWorker] -- no DI framework yet. No-ops if sync is disabled or the user isn't
 * signed in; either is a normal, expected state, not a failure.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val appPrefs = AppPreferences(
            SharedPreferencesSettings(applicationContext.getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        if (!appPrefs.syncEnabled.value) return Result.success()

        val authManager = createGoogleAuthManager(applicationContext)
        if (!authManager.isSignedIn()) return Result.success()

        val database = createMangaDatabase(DatabaseDriverFactory(applicationContext).create())
        val repository = LibraryRepository(database)

        return try {
            // One Drive backend instance covers both files (progress.json,
            // metadata_aliases.json, PLAN.md §10) -- same auth/HTTP plumbing either way.
            val driveBackend = GoogleDriveSyncBackend(authManager)
            // Settings' "Sync fixed metadata" toggle -- off means never pull/push
            // metadata_aliases.json, without the coordinator needing to know why.
            val aliasBackend = if (appPrefs.metadataAliasSyncEnabled.value) driveBackend else NoOpMetadataAliasBackend
            // Only record an alias "last synced" timestamp when the toggle is actually on -- otherwise
            // the byline would claim a metadata_aliases.json sync that never happened (PLAN.md §10).
            val onAliasSyncCompleted: () -> Unit = { if (appPrefs.metadataAliasSyncEnabled.value) appPrefs.recordMetadataAliasSyncCompleted() }
            ProgressSyncCoordinator(repository, driveBackend, aliasBackend, appPrefs::recordSyncCompleted, onAliasSyncCompleted).sync()
            Result.success()
        } catch (t: Throwable) {
            android.util.Log.w("SyncWorker", "background sync failed", t)
            Result.retry()
        }
    }
}

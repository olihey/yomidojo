package com.oliver.heyme.mangazuki

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.oliver.heyme.mangazuki.core.data.DatabaseDriverFactory
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.createMangaDatabase
import com.oliver.heyme.mangazuki.core.sync.GoogleDriveSyncBackend
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
            ProgressSyncCoordinator(repository, GoogleDriveSyncBackend(authManager), appPrefs::recordSyncCompleted).sync()
            Result.success()
        } catch (t: Throwable) {
            android.util.Log.w("SyncWorker", "background sync failed", t)
            Result.retry()
        }
    }
}

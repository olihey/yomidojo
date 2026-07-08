package com.oliver.heyme.mangazuki

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.oliver.heyme.mangazuki.core.data.DatabaseDriverFactory
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.createMangaDatabase
import com.oliver.heyme.mangazuki.core.metadata.AniListMetadataProvider
import com.oliver.heyme.mangazuki.core.metadata.KitsuMetadataProvider
import com.oliver.heyme.mangazuki.core.scanner.LibraryScanner
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.HttpClient

/**
 * Background library scan (PLAN.md §15) so a large scan survives leaving the screen and the
 * library stays fresh. Builds its own object graph from the app context (no DI framework yet)
 * and reuses [LibrarySyncer]. No-ops if no root is saved or the SAF grant was revoked. Also
 * runs AniList enrichment (PLAN.md §9.2) for whatever's still unmatched — unlike the
 * foreground fire-and-forget trigger in [LibraryViewModel], this *is* the background work,
 * so it's awaited here rather than launched separately.
 */
class ScanWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val database = createMangaDatabase(DatabaseDriverFactory(applicationContext).create())
        val repository = LibraryRepository(database)

        // savedLocalRoot() holds a raw SAF URI for LOCAL, or an SmbConfig/OneDriveConfig blob
        // otherwise — resolve both the right MangaSource and the right scan locator from `type`.
        val type = repository.savedSourceType()
        val configBlob = repository.savedLocalRoot() ?: return Result.success()
        val (source, root) = when (type) {
            "SMB" -> {
                val smbConfig = SmbConfig.fromBlob(configBlob) ?: return Result.success()
                val password = AndroidSmbSourceFactory(applicationContext).loadPassword() ?: ""
                SmbMangaSource(smbConfig.host, smbConfig.share, smbConfig.username, password) to smbConfig.rootPath
            }
            "ONEDRIVE" -> {
                // Token refresh works from a worker too: MicrosoftAuthStore is process-wide, and
                // a signed-out/offline run fails the canAccess guard below into a clean no-op.
                val auth = createMicrosoftAuthManager(applicationContext)
                OneDriveMangaSource(auth::accessToken) to OneDriveConfig.fromBlob(configBlob).rootPath
            }
            else -> SafMangaSource(applicationContext) to configBlob
        }
        if (!source.canAccess(root)) return Result.success() // grant lost; UI will prompt re-grant

        return try {
            LibrarySyncer(repository, LibraryScanner(source)).sync(root)
            val coversDir = applicationContext.filesDir.resolve("covers").absolutePath
            // Fresh AppPreferences from the same backing store the Activity uses — this worker
            // builds its own throwaway object graph per run, same pattern as source selection
            // above, so it always reads whatever provider is currently selected in Settings.
            val appPrefs = AppPreferences(
                SharedPreferencesSettings(applicationContext.getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
            )
            val providers = MetadataProviders(AniListMetadataProvider(), KitsuMetadataProvider())
            MetadataEnricher(
                repository, { providers.get(appPrefs.metadataProvider.value) }, providers::byName, HttpClient(), coversDir,
            ).enrichPending()
            Result.success()
        } catch (t: Throwable) {
            android.util.Log.w("ScanWorker", "background scan failed", t)
            Result.retry()
        }
    }
}

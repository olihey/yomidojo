package com.mangaread

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.data.DatabaseDriverFactory
import com.mangaread.core.data.createMangaDatabase
import com.mangaread.core.metadata.AniListMetadataProvider
import com.mangaread.core.scanner.LibraryScanner
import com.russhwolf.settings.SharedPreferencesSettings
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.key.Keyer
import coil3.request.Options
import io.ktor.client.HttpClient
import okio.Path.Companion.toOkioPath
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LibraryViewModel
    private lateinit var pickFolder: ActivityResultLauncher<Uri?>
    private lateinit var readerPrefs: ReaderPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val source = SafMangaSource(applicationContext)
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components {
                    add(Keyer<MangaCover> { cover, _: Options -> cover.model })
                    add(CoverFetcher.Factory(applicationContext, source))
                    add(Keyer<MangaPage> { page, _: Options -> "${page.model}#${page.index}" })
                    add(PageFetcher.Factory(applicationContext, source))
                }
                // Explicit (rather than relying on Coil's default) so covers/pages extracted
                // on demand from CBZ/folders are only ever extracted once and survive restarts.
                .diskCache {
                    DiskCache.Builder()
                        .directory(ctx.cacheDir.resolve("image_cache").toOkioPath())
                        .maxSizePercent(0.05)
                        .build()
                }
                .build()
        }

        val database = createMangaDatabase(DatabaseDriverFactory(applicationContext).create())
        val repository = LibraryRepository(database)
        val scanner = LibraryScanner(source)
        val prefs = LibraryPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        val metadataProvider = AniListMetadataProvider()
        val coversDir = applicationContext.filesDir.resolve("covers").absolutePath
        val coverClient = HttpClient()
        val enricher = MetadataEnricher(repository, metadataProvider, coverClient, coversDir)
        val appPrefs = AppPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        viewModel = LibraryViewModel(repository, scanner, source, prefs, enricher, appPrefs)
        readerPrefs = ReaderPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        val graph = AppGraph(
            repository, source, viewModel, readerPrefs, appPrefs,
            metadataProvider, enricher, coverClient, coversDir,
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

        scheduleBackgroundScan()

        enableEdgeToEdge()
        setContent { App(graph) { pickFolder.launch(null) } }
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
}

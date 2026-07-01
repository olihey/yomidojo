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
import com.mangaread.core.scanner.LibraryScanner
import com.russhwolf.settings.SharedPreferencesSettings
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.key.Keyer
import coil3.request.Options
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LibraryViewModel
    private lateinit var pickFolder: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val source = SafMangaSource(applicationContext)
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components {
                    add(Keyer<MangaCover> { cover, _: Options -> cover.model })
                    add(CoverFetcher.Factory(applicationContext, source))
                }
                .build()
        }

        val database = createMangaDatabase(DatabaseDriverFactory(applicationContext).create())
        val repository = LibraryRepository(database)
        val scanner = LibraryScanner(source)
        val prefs = LibraryPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        viewModel = LibraryViewModel(repository, scanner, source, prefs)

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
        setContent { App(viewModel) { pickFolder.launch(null) } }
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

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

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: LibraryViewModel
    private lateinit var pickFolder: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = createMangaDatabase(DatabaseDriverFactory(applicationContext).create())
        val repository = LibraryRepository(database)
        val scanner = LibraryScanner(SafMangaSource(applicationContext))
        val prefs = LibraryPreferences(
            SharedPreferencesSettings(getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)),
        )
        viewModel = LibraryViewModel(repository, scanner, prefs)

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

        enableEdgeToEdge()
        setContent { App(viewModel) { pickFolder.launch(null) } }
    }
}

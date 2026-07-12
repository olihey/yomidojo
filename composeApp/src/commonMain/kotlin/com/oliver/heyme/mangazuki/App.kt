package com.oliver.heyme.mangazuki

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Library → Series → Reader (PLAN.md §7.3), a plain nav-compose push stack. */
@Composable
fun App(
    graph: AppGraph,
    onPickFolder: () -> Unit,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    // OneDrive source sign-in (PLAN.md §6.3) -- an Activity-owned callback/state pair like
    // onSignIn/syncState, but terminating at LibraryScreen's connect dialog instead of Settings.
    oneDriveAuthState: StateFlow<OneDriveAuthState> = MutableStateFlow(OneDriveAuthState.SignedOut),
    onOneDriveSignIn: () -> Unit = {},
) {
    val navController = rememberNavController()
    val titleLanguage by graph.appPreferences.titleLanguage.collectAsState()
    // Always dark: the app's whole visual language (MangaColors, the shelf/detail designs) is
    // built on a dark palette, so the light/system theme options were dropped.
    MaterialTheme(colorScheme = darkColorScheme()) {
        NavHost(navController = navController, startDestination = "library") {
            composable("library") {
                LibraryScreen(
                    viewModel = graph.libraryViewModel,
                    onPickFolder = onPickFolder,
                    onSeriesClick = { seriesId -> navController.navigate("series/$seriesId") },
                    // "Your Page" resume/fresh-chapter cards jump straight into the reader,
                    // skipping the series screen (PLAN.md).
                    onChapterClick = { seriesId, chapterId -> navController.navigate("reader/$seriesId/$chapterId") },
                    onSettingsClick = { navController.navigate("settings") },
                    titleLanguage = titleLanguage,
                    // A one-time seed for the Library/Your Page tab's rememberSaveable initial
                    // value (PLAN.md), not observed reactively -- changing this setting mid-session
                    // shouldn't yank the user off whichever tab they're already on.
                    startScreen = graph.appPreferences.startScreen.value,
                    oneDriveAuthState = oneDriveAuthState,
                    onOneDriveSignIn = onOneDriveSignIn,
                )
            }
            composable("settings") {
                SettingsScreen(
                    prefs = graph.readerPreferences,
                    appPreferences = graph.appPreferences,
                    onBack = { navController.popBackStack() },
                    onResetLibrary = graph.libraryViewModel::resetLibrary,
                    syncState = graph.syncState,
                    onSignIn = onSignIn,
                    onSignOut = onSignOut,
                    onBackgroundSyncEnabledChanged = graph.onBackgroundSyncEnabledChanged,
                    fetchProgressJson = graph.fetchProgressJson,
                    fetchMetadataAliasesJson = graph.fetchMetadataAliasesJson,
                    fetchFavoritesJson = graph.fetchFavoritesJson,
                    clearProgressJson = graph.clearProgressJson,
                    clearMetadataAliasesJson = graph.clearMetadataAliasesJson,
                    clearFavoritesJson = graph.clearFavoritesJson,
                    exportJsonFile = graph.exportJsonFile,
                    pickJsonFile = graph.pickJsonFile,
                    importProgressJson = graph.importProgressJson,
                    importMetadataAliasesJson = graph.importMetadataAliasesJson,
                    importFavoritesJson = graph.importFavoritesJson,
                    isDebugBuild = graph.isDebugBuild,
                )
            }
            composable("series/{seriesId}") { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                val viewModel = remember(seriesId) {
                    SeriesViewModel(
                        graph.repository, graph.source, seriesId,
                        graph.metadataProviders, graph.appPreferences, graph.coverClient, graph.coversDir,
                        requestSync = graph.requestSync,
                    )
                }
                SeriesScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onChapterClick = { chapterId -> navController.navigate("reader/$seriesId/$chapterId") },
                    titleLanguage = titleLanguage,
                )
            }
            composable("reader/{seriesId}/{chapterId}") { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                val chapterId = entry.arguments?.getString("chapterId") ?: return@composable
                ReaderHost(
                    graph, seriesId, chapterId,
                    onBack = { navController.popBackStack() },
                    // Swiping past the last page into the next-chapter preview replaces this
                    // back-stack entry, so "back" from the next chapter returns to the series
                    // screen rather than stepping backward chapter by chapter.
                    onNavigateToChapter = { nextChapterId ->
                        navController.navigate("reader/$seriesId/$nextChapterId") {
                            popUpTo("reader/$seriesId/$chapterId") { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderHost(
    graph: AppGraph,
    seriesId: String,
    chapterId: String,
    onBack: () -> Unit,
    onNavigateToChapter: (String) -> Unit,
) {
    val series by remember(seriesId) { graph.repository.observeSeries(seriesId) }.collectAsState(initial = null)
    val chapters by remember(seriesId) { graph.repository.observeChapters(seriesId) }.collectAsState(initial = emptyList())
    val chapter = chapters.find { it.id == chapterId } ?: return
    val titleLanguage by graph.appPreferences.titleLanguage.collectAsState()

    val seriesDirection = series?.readingDirection
    val title = series?.displayTitle(titleLanguage) ?: ""
    val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
    val nextChapter = if (chapterIndex in 0 until chapters.lastIndex) chapters[chapterIndex + 1] else null
    val viewModel = remember(chapter.id, seriesDirection, title) {
        ReaderViewModel(
            graph.repository, graph.source, chapter, seriesDirection, title, nextChapter,
            graph.readerPreferences, graph.appPreferences.deviceId,
            requestSync = graph.requestSync,
            pdfCacheDir = graph.pdfCacheDir,
        )
    }
    ReaderScreen(viewModel, onBack, onNavigateToChapter)
}

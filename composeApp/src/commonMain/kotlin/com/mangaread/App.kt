package com.mangaread

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/** Library → Series → Reader (PLAN.md §7.3), a plain nav-compose push stack. */
@Composable
fun App(graph: AppGraph, onPickFolder: () -> Unit) {
    val navController = rememberNavController()
    val themeMode by graph.appPreferences.themeMode.collectAsState()
    val titleLanguage by graph.appPreferences.titleLanguage.collectAsState()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        NavHost(navController = navController, startDestination = "library") {
            composable("library") {
                LibraryScreen(
                    viewModel = graph.libraryViewModel,
                    onPickFolder = onPickFolder,
                    onSeriesClick = { seriesId -> navController.navigate("series/$seriesId") },
                    onSettingsClick = { navController.navigate("settings") },
                    titleLanguage = titleLanguage,
                )
            }
            composable("settings") {
                SettingsScreen(
                    prefs = graph.readerPreferences,
                    appPreferences = graph.appPreferences,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("series/{seriesId}") { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                val viewModel = remember(seriesId) {
                    SeriesViewModel(
                        graph.repository, graph.source, seriesId,
                        graph.metadataProvider, graph.coverClient, graph.coversDir,
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
        ReaderViewModel(graph.repository, graph.source, chapter, seriesDirection, title, nextChapter, graph.readerPreferences)
    }
    ReaderScreen(viewModel, onBack, onNavigateToChapter)
}

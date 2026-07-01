package com.mangaread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mangaread.core.domain.ReadingDirection

/** Library → Series → Reader (PLAN.md §7.3), a plain nav-compose push stack. */
@Composable
fun App(graph: AppGraph, onPickFolder: () -> Unit) {
    val navController = rememberNavController()
    MaterialTheme {
        NavHost(navController = navController, startDestination = "library") {
            composable("library") {
                LibraryScreen(
                    viewModel = graph.libraryViewModel,
                    onPickFolder = onPickFolder,
                    onSeriesClick = { seriesId -> navController.navigate("series/$seriesId") },
                )
            }
            composable("series/{seriesId}") { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                val viewModel = remember(seriesId) { SeriesViewModel(graph.repository, seriesId) }
                SeriesScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onChapterClick = { chapterId -> navController.navigate("reader/$seriesId/$chapterId") },
                )
            }
            composable("reader/{seriesId}/{chapterId}") { entry ->
                val seriesId = entry.arguments?.getString("seriesId") ?: return@composable
                val chapterId = entry.arguments?.getString("chapterId") ?: return@composable
                ReaderHost(graph, seriesId, chapterId, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun ReaderHost(graph: AppGraph, seriesId: String, chapterId: String, onBack: () -> Unit) {
    val series by remember(seriesId) { graph.repository.observeSeries(seriesId) }.collectAsState(initial = null)
    val chapters by remember(seriesId) { graph.repository.observeChapters(seriesId) }.collectAsState(initial = emptyList())
    val chapter = chapters.find { it.id == chapterId } ?: return

    val rtl = series?.readingDirection != ReadingDirection.LTR
    val title = series?.title ?: ""
    val viewModel = remember(chapter.id, rtl, title) {
        ReaderViewModel(graph.repository, graph.source, chapter, rtl, title, graph.readerPreferences)
    }
    ReaderScreen(viewModel, onBack)
}

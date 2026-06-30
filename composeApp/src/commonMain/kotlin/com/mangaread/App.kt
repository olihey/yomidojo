package com.mangaread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun App(viewModel: LibraryViewModel, onPickFolder: () -> Unit) {
    MaterialTheme {
        val series by viewModel.series.collectAsState()
        val progress by viewModel.progress.collectAsState()
        val canRescan by viewModel.canRescan.collectAsState()
        LibraryScreen(
            series = series,
            progress = progress,
            canRescan = canRescan,
            onPickFolder = onPickFolder,
            onRescan = viewModel::rescan,
        )
    }
}

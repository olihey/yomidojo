package com.mangaread

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun App(viewModel: LibraryViewModel, onPickFolder: () -> Unit) {
    MaterialTheme {
        LibraryScreen(viewModel = viewModel, onPickFolder = onPickFolder)
    }
}

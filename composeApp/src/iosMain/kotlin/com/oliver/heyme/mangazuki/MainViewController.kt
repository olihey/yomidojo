package com.oliver.heyme.mangazuki

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.window.ComposeUIViewController

// iOS wiring (NativeSqliteDriver + a bookmark-backed MangaSource + folder picker) lands at
// iOS bring-up — deferred until a Mac is available (PLAN.md §12, §16).
fun MainViewController() = ComposeUIViewController {
    MaterialTheme { Text("iOS bring-up pending") }
}

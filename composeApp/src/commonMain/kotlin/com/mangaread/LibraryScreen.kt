package com.mangaread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangaread.core.domain.Series

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    series: List<Series>,
    progress: ScanProgress?,
    canRescan: Boolean,
    onPickFolder: () -> Unit,
    onRescan: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (progress != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Text("Scanning… ${progress.seriesFound} series, ${progress.chaptersFound} chapters")
                        }
                    } else {
                        Text("Library (${series.size})")
                    }
                },
                actions = {
                    if (canRescan && progress == null) {
                        TextButton(onClick = onRescan) { Text("Re-scan") }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onPickFolder) { Text("+", Modifier.padding(4.dp)) }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (series.isEmpty() && progress == null) {
                Text(
                    "No series yet — tap + to pick your manga folder.",
                    Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().fillMaxWidth()) {
                    items(series, key = { it.id }) { s ->
                        ListItem(headlineContent = { Text(s.title) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

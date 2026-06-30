package com.mangaread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Walking-skeleton root (PLAN.md §13). Phase 0 proves the layers thread together;
 * the real Library → Series → Reader navigation lands in Phases 1–2.
 */
@Composable
fun App() {
    MaterialTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Manga Reader — Phase 0 skeleton")
        }
    }
}

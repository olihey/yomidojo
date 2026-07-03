package com.oliver.heyme.mangazuki

import androidx.compose.runtime.Composable

/** Hides the system status/navigation bars while active, restoring them on dispose (PLAN.md §8.1). */
@Composable
expect fun ImmersiveMode(enabled: Boolean)

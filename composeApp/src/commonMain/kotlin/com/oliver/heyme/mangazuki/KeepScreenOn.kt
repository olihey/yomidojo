package com.oliver.heyme.mangazuki

import androidx.compose.runtime.Composable

/** Prevents the display from sleeping while active (PLAN.md §8.1). No-op where not wired. */
@Composable
expect fun KeepScreenOn(enabled: Boolean)

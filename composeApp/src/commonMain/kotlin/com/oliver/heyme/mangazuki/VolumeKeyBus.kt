package com.oliver.heyme.mangazuki

/**
 * Lets the (Android-only) hardware volume keys drive the active reader without the
 * Activity needing a reference to the current Composable (PLAN.md §8.1). The reader
 * registers a handler while visible; `true` return means "consumed, don't adjust volume".
 */
object VolumeKeyBus {
    var onVolumeKey: ((down: Boolean) -> Boolean)? = null
}

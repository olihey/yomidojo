package com.oliver.heyme.mangazuki

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesces rapid-fire progress writes (e.g. flipping through pages while reading) into a single
 * deferred sync (PLAN.md §10), rather than a full Drive round-trip -- a few HTTP calls, several
 * seconds observed on-device -- on every single page turn. Each [requestSync] call resets the
 * debounce window; the sync only actually runs once [debounceMillis] pass with no further calls.
 */
class ProgressSyncScheduler(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 5_000,
    private val sync: suspend () -> Unit,
) {
    private var pending: Job? = null

    fun requestSync() {
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMillis)
            sync()
        }
    }
}

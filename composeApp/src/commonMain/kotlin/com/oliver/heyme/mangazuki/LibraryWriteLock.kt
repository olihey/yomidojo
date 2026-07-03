package com.oliver.heyme.mangazuki

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes every [LibrarySyncer.sync] and [MetadataEnricher.enrichPending] call app-wide
 * (PLAN.md §9.2's "Known issue," reproduced 2026-07-02). The foreground scan trigger and the
 * background `ScanWorker` each build their own independent `LibrarySyncer`/`MetadataEnricher`
 * against the same on-disk database, so without a shared lock two overlapping runs can race on
 * `deleteSeriesNotScannedAt`: each deletes any series row not stamped with *its own* scan
 * timestamp, including rows the other run just wrote — silently wiping applied AniList metadata
 * even though the underlying scan-then-reconcile logic is correct for any single, non-overlapping
 * pass. A single process-wide `Mutex` is sufficient here since both entry points run in the same
 * app process (confirmed via `adb shell ps` while reproducing the bug — WorkManager doesn't use a
 * separate process for this app).
 */
internal val libraryWriteMutex = Mutex()

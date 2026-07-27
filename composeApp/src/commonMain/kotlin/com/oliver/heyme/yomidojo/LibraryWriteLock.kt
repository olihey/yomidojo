package com.oliver.heyme.yomidojo

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * The [Job] of whichever [MetadataEnricher.enrichPending] call currently holds
 * [libraryWriteMutex], if any (PLAN.md §9.2, 2026-07-06) -- lets [LibrarySyncer.sync] cancel a
 * still-running enrichment pass instead of waiting behind it for however long AniList's
 * rate-limited queue takes to drain. Shared/top-level rather than owned by whichever caller
 * happens to have kicked enrichment off, since the foreground UI's own trigger and the background
 * `ScanWorker` build independent `MetadataEnricher` instances in independent coroutine scopes --
 * neither can see or cancel a Job the other one started without a shared handle like this one.
 */
internal val currentEnrichmentJob = MutableStateFlow<Job?>(null)

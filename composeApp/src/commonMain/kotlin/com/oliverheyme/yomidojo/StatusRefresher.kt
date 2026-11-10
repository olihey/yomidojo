package com.oliverheyme.yomidojo

import com.oliverheyme.yomidojo.core.data.LibraryRepository
import com.oliverheyme.yomidojo.core.domain.nowEpochMillis
import com.oliverheyme.yomidojo.core.metadata.MetadataProvider
import kotlinx.coroutines.CancellationException

const val STATUS_REFRESH_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Weekly publication-status refresh (PLAN.md §9.5). Enrichment fetches a series' metadata only
 * once, when it's first matched, but a RELEASING series can later become FINISHED (or go on
 * HIATUS). This re-fetches just the status for every matched, not-yet-FINISHED series using each
 * provider's batched lookup, so a whole library costs a handful of requests rather than one per
 * series. Best-effort: a failed request leaves the timestamp unrecorded, so the next scan retries.
 */
class StatusRefresher(
    private val repository: LibraryRepository,
    private val providerNamed: (String) -> MetadataProvider?,
    private val prefs: AppPreferences,
    private val now: () -> Long = ::nowEpochMillis,
) {
    /** [force] skips the once-a-week check — used by the manual rescan button. */
    suspend fun refreshIfDue(force: Boolean = false) {
        val last = prefs.lastStatusRefreshAt
        if (!force && last != null && now() - last < STATUS_REFRESH_INTERVAL_MS) return

        var allSucceeded = true
        repository.seriesForStatusRefresh().groupBy({ it.first }, { it.second }).forEach { (providerName, externalIds) ->
            val provider = providerNamed(providerName) ?: return@forEach
            try {
                repository.applyStatuses(providerName, provider.statuses(externalIds))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                allSucceeded = false
            }
        }
        if (allSucceeded) prefs.recordStatusRefreshCompleted(now())
    }
}

package com.mangaread

import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.Series
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.scanner.LibraryScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** null when idle; non-null with running counts while a scan is in progress. */
data class ScanProgress(val seriesFound: Int, val chaptersFound: Int)

/**
 * Phase 1 spine: observe the library reactively from the DB, run a scan that persists each
 * series as it's found (so the list fills in live), and remember the granted library root so
 * the user can re-scan with one tap. Plain class (commonMain); the SAF folder pick is supplied
 * by the platform layer.
 */
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val scanner: LibraryScanner,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    val series: StateFlow<List<Series>> =
        repository.observeSeries().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    /** True once a library root has been picked — enables the re-scan action. */
    private val _canRescan = MutableStateFlow(false)
    val canRescan: StateFlow<Boolean> = _canRescan

    init {
        scope.launch { _canRescan.value = repository.savedLocalRoot() != null }
    }

    /** Called after the user picks a folder: remember it, then scan. */
    fun onFolderPicked(rootLocator: String, displayName: String) {
        scope.launch {
            repository.saveLocalRoot(rootLocator, displayName)
            _canRescan.value = true
            runScan(rootLocator)
        }
    }

    /** Re-scan the remembered library root (reconciles via deterministic-ID upsert). */
    fun rescan() {
        scope.launch { repository.savedLocalRoot()?.let { runScan(it) } }
    }

    private suspend fun runScan(rootLocator: String) {
        if (_progress.value != null) return // already scanning
        var seriesCount = 0
        var chapterCount = 0
        _progress.value = ScanProgress(0, 0)
        try {
            scanner.scan(rootLocator, nowEpochMillis()).collect { scanned ->
                repository.persistSeries(scanned.series, scanned.chapters)
                seriesCount++
                chapterCount += scanned.chapters.size
                _progress.value = ScanProgress(seriesCount, chapterCount)
            }
        } finally {
            _progress.value = null
        }
    }
}

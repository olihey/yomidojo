package com.mangaread

import com.mangaread.core.data.LibraryCard
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.scanner.LibraryScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScanProgress(val seriesFound: Int, val chaptersFound: Int)

enum class SortMode(val label: String) { NAME("Name"), RECENTLY_ADDED("Recently added") }
enum class ViewMode { LIST, GRID, DETAILED }

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val scanner: LibraryScanner,
    private val prefs: LibraryPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    private val _canRescan = MutableStateFlow(false)
    val canRescan: StateFlow<Boolean> = _canRescan

    // Library controls (PLAN §7.1) — initial values restored from persisted prefs.
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(prefs.sort)
    val ascending = MutableStateFlow(prefs.ascending)
    val unreadOnly = MutableStateFlow(prefs.unreadOnly)
    val viewMode = MutableStateFlow(prefs.viewMode)

    private val allCards: StateFlow<List<LibraryCard>> =
        repository.observeLibrary().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Filtered + sorted cards the UI renders. */
    val cards: StateFlow<List<LibraryCard>> =
        combine(allCards, query, sort, ascending, unreadOnly) { cards, q, sortMode, asc, unread ->
            var list = cards
            if (q.isNotBlank()) list = list.filter { it.title.contains(q, ignoreCase = true) }
            if (unread) list = list.filter { it.unreadCount > 0 }
            val comparator: Comparator<LibraryCard> = when (sortMode) {
                SortMode.NAME -> compareBy { it.sortTitle }
                SortMode.RECENTLY_ADDED -> compareBy { it.latestChapterAdded }
            }
            list.sortedWith(if (asc) comparator else comparator.reversed())
        }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        scope.launch { _canRescan.value = repository.savedLocalRoot() != null }
        // Persist view preferences whenever they change (first emit re-writes the current value, harmless).
        scope.launch { viewMode.collect { prefs.viewMode = it } }
        scope.launch { sort.collect { prefs.sort = it } }
        scope.launch { ascending.collect { prefs.ascending = it } }
        scope.launch { unreadOnly.collect { prefs.unreadOnly = it } }
    }

    fun onFolderPicked(rootLocator: String, displayName: String) {
        scope.launch {
            repository.saveLocalRoot(rootLocator, displayName)
            _canRescan.value = true
            runScan(rootLocator)
        }
    }

    fun rescan() {
        scope.launch { repository.savedLocalRoot()?.let { runScan(it) } }
    }

    fun toggleDirection() { ascending.value = !ascending.value }
    fun cycleViewMode() {
        viewMode.value = ViewMode.entries[(viewMode.value.ordinal + 1) % ViewMode.entries.size]
    }

    private suspend fun runScan(rootLocator: String) {
        if (_progress.value != null) return
        var seriesCount = 0
        var chapterCount = 0
        val scanAt = nowEpochMillis()
        _progress.value = ScanProgress(0, 0)
        try {
            scanner.scan(rootLocator, scanAt).collect { scanned ->
                repository.persistSeries(scanned.series, scanned.chapters)
                seriesCount++
                chapterCount += scanned.chapters.size
                _progress.value = ScanProgress(seriesCount, chapterCount)
            }
            // Reached only if the scan completed without throwing: safe to prune removed series.
            repository.deleteSeriesNotScannedAt(scanAt)
        } finally {
            _progress.value = null
        }
    }
}

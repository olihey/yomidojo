package com.mangaread

import com.mangaread.core.data.LibraryCard
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.data.RecentChapter
import com.mangaread.core.scanner.LibraryScanner
import com.mangaread.core.source.MangaSource
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
    scanner: LibraryScanner,
    private val source: MangaSource,
    private val prefs: LibraryPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val syncer = LibrarySyncer(repository, scanner)

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    private val _canRescan = MutableStateFlow(false)
    val canRescan: StateFlow<Boolean> = _canRescan

    /** True when a library root is saved but its SAF permission was lost (PLAN.md §12). */
    private val _needsReGrant = MutableStateFlow(false)
    val needsReGrant: StateFlow<Boolean> = _needsReGrant

    val query = MutableStateFlow("")
    val sort = MutableStateFlow(prefs.sort)
    val ascending = MutableStateFlow(prefs.ascending)
    val unreadOnly = MutableStateFlow(prefs.unreadOnly)
    val viewMode = MutableStateFlow(prefs.viewMode)

    private val allCards: StateFlow<List<LibraryCard>> =
        repository.observeLibrary().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    val recent: StateFlow<List<RecentChapter>> =
        repository.observeRecentlyAdded().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        scope.launch {
            val root = repository.savedLocalRoot()
            _canRescan.value = root != null
            if (root != null && !source.canAccess(root)) _needsReGrant.value = true
        }
        scope.launch { viewMode.collect { prefs.viewMode = it } }
        scope.launch { sort.collect { prefs.sort = it } }
        scope.launch { ascending.collect { prefs.ascending = it } }
        scope.launch { unreadOnly.collect { prefs.unreadOnly = it } }
    }

    fun onFolderPicked(rootLocator: String, displayName: String) {
        scope.launch {
            repository.saveLocalRoot(rootLocator, displayName)
            _canRescan.value = true
            _needsReGrant.value = false
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
        if (!source.canAccess(rootLocator)) { _needsReGrant.value = true; return }
        _progress.value = ScanProgress(0, 0)
        try {
            syncer.sync(rootLocator) { s, c -> _progress.value = ScanProgress(s, c) }
        } finally {
            _progress.value = null
        }
    }
}

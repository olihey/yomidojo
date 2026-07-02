package com.mangaread

import com.mangaread.core.data.LibraryCard
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.normalizeSortTitle
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

enum class SortMode(val label: String) {
    NAME("Name"), RECENTLY_ADDED("Recently added"), RECENTLY_READ("Recently read"), RELEASE_START("Release start")
}
enum class ViewMode { LIST, GRID, DETAILED }

class LibraryViewModel(
    private val repository: LibraryRepository,
    scanner: LibraryScanner,
    private val source: MangaSource,
    private val prefs: LibraryPreferences,
    private val enricher: MetadataEnricher,
    private val appPreferences: AppPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val syncer = LibrarySyncer(repository, scanner)

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    private val _canRescan = MutableStateFlow(false)
    val canRescan: StateFlow<Boolean> = _canRescan

    /** True while the AniList enrichment pass kicked off by [runScan] is still working through
     * [MetadataEnricher.enrichPending] (PLAN.md §9.2) — separate from [progress] since enrichment
     * keeps running well after the scan itself (and its progress UI) finishes. */
    private val _enriching = MutableStateFlow(false)
    val enriching: StateFlow<Boolean> = _enriching

    /** True when a library root is saved but its SAF permission was lost (PLAN.md §12). */
    private val _needsReGrant = MutableStateFlow(false)
    val needsReGrant: StateFlow<Boolean> = _needsReGrant

    val query = MutableStateFlow("")
    val sort = MutableStateFlow(prefs.sort)
    val ascending = MutableStateFlow(prefs.ascending)
    val unreadOnly = MutableStateFlow(prefs.unreadOnly)
    /** Always grid on a fresh launch, regardless of what was last used (cycleViewMode still
     * switches within the session, it just isn't restored on restart). */
    val viewMode = MutableStateFlow(ViewMode.GRID)

    /** Multi-select series for bulk mark read/unread (PLAN.md §7.5). */
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    private val allCards: StateFlow<List<LibraryCard>> =
        repository.observeLibrary().stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private data class FilterInputs(
        val cards: List<LibraryCard>,
        val query: String,
        val sortMode: SortMode,
        val ascending: Boolean,
        val unreadOnly: Boolean,
    )

    val cards: StateFlow<List<LibraryCard>> =
        combine(allCards, query, sort, ascending, unreadOnly, ::FilterInputs)
            .combine(appPreferences.titleLanguage) { inputs, titleLanguage ->
                var list = inputs.cards
                if (inputs.query.isNotBlank()) list = list.filter { it.title.contains(inputs.query, ignoreCase = true) }
                if (inputs.unreadOnly) list = list.filter { it.unreadCount > 0 }
                val comparator: Comparator<LibraryCard> = when (inputs.sortMode) {
                    // Sorts by whichever title is currently displayed (PLAN.md §9), not always
                    // the file name — normalized the same way `sort_title` is (§10) so ordering
                    // stays locale/punctuation-insensitive regardless of which title is active.
                    SortMode.NAME -> compareBy { normalizeSortTitle(it.displayTitle(titleLanguage)) }
                    SortMode.RECENTLY_ADDED -> compareBy { it.latestChapterAdded }
                    SortMode.RECENTLY_READ -> compareBy { it.latestRead ?: 0L }
                    // Unmatched series (no AniList start_year yet) sort after all matched ones,
                    // ordered among themselves by date-added (PLAN.md §7.1's specified fallback).
                    SortMode.RELEASE_START -> compareBy({ it.startYear == null }, { it.startYear ?: 0 }, { it.latestChapterAdded })
                }
                list.sortedWith(if (inputs.ascending) comparator else comparator.reversed())
            }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        scope.launch {
            val root = repository.savedLocalRoot()
            _canRescan.value = root != null
            if (root != null && !source.canAccess(root)) _needsReGrant.value = true
        }
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

    fun enterSelectionMode(seriesId: String) {
        selectionMode.value = true
        selectedIds.value = setOf(seriesId)
    }

    fun toggleSelected(seriesId: String) {
        selectedIds.value = if (seriesId in selectedIds.value) selectedIds.value - seriesId else selectedIds.value + seriesId
        if (selectedIds.value.isEmpty()) selectionMode.value = false
    }

    fun selectAll() { selectedIds.value = cards.value.map { it.id }.toSet() }
    fun selectNone() { selectedIds.value = emptySet() }

    fun exitSelectionMode() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    fun markSelectedRead(completed: Boolean) {
        val ids = selectedIds.value.toList()
        scope.launch {
            repository.markSeriesProgress(ids, completed)
            exitSelectionMode()
        }
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
        // Fire-and-forget: enrichment is rate-limited and best-effort (PLAN.md §9.2), so it
        // shouldn't hold up the scan-progress UI or the library screen becoming usable. Its own
        // [_enriching] flag lets the UI show it's still fetching well after the scan is done.
        scope.launch {
            _enriching.value = true
            try {
                enricher.enrichPending()
            } finally {
                _enriching.value = false
            }
        }
    }
}

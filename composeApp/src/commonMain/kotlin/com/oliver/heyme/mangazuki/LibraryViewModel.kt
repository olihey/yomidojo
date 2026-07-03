package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.LibraryCard
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.normalizeSortTitle
import com.oliver.heyme.mangazuki.core.scanner.LibraryScanner
import com.oliver.heyme.mangazuki.core.source.MangaSource
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

/** [done]/[total] series processed by the current [MetadataEnricher.enrichPending] pass
 * (PLAN.md §9.2) — "processed" includes matched, checked-no-match, and failed alike. */
data class EnrichProgress(val done: Int, val total: Int)

enum class SortMode(val label: String) {
    NAME("Name"), RECENTLY_ADDED("Recently added"), RECENTLY_READ("Recently read"), RELEASE_START("Release start")
}
enum class ViewMode { LIST, GRID, DETAILED }

/** Library filter (PLAN.md §7.1): "Hide matched" is the AniList-match counterpart of "Hide
 * read" — hides series that already have an `external_id`, for focusing on what Fix Metadata
 * still needs to look at (PLAN.md §9.1). */
enum class LibraryFilter(val label: String) {
    SHOW_ALL("Show all"), HIDE_READ("Hide read"), HIDE_MATCHED("Hide matched")
}

class LibraryViewModel(
    private val repository: LibraryRepository,
    scanner: LibraryScanner,
    private val source: MangaSource,
    /** The original local (SAF) source instance — reused as-is when switching back to a
     * local folder, since it's just a stateless wrapper around the platform Context. */
    private val localSource: MangaSource,
    /** Null on platforms without SMB support yet (PLAN.md §6) — [onSmbConnect] no-ops. */
    private val smbSourceFactory: SmbSourceFactory?,
    private val prefs: LibraryPreferences,
    private val enricher: MetadataEnricher,
    private val appPreferences: AppPreferences,
    /** App-internal cover/banner storage (PLAN.md §9, §9.4) — [resetLibrary] clears it directly
     * since it owns this path, same as [downloadCover]/`CoverFetcher` do when writing to it. */
    private val coversDir: String,
    /** Clears the platform image loader's own cache (PLAN.md §7.1) — deterministic series/chapter
     * IDs (§5) mean a later re-scan of the same folder recomputes the *same* IDs, so without this
     * a stale cached bitmap could resurface under an old cache key even after a full DB reset. */
    private val clearImageCache: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val syncer = LibrarySyncer(repository, scanner)

    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress

    private val _canRescan = MutableStateFlow(false)
    val canRescan: StateFlow<Boolean> = _canRescan

    /** Non-null while the enrichment pass kicked off by [runScan] is still working through
     * [MetadataEnricher.enrichPending] (PLAN.md §9.2) — separate from [progress] since enrichment
     * keeps running well after the scan itself (and its progress UI) finishes. */
    private val _enrichProgress = MutableStateFlow<EnrichProgress?>(null)
    val enrichProgress: StateFlow<EnrichProgress?> = _enrichProgress

    /** True when a library root is saved but its SAF permission was lost (PLAN.md §12). */
    private val _needsReGrant = MutableStateFlow(false)
    val needsReGrant: StateFlow<Boolean> = _needsReGrant

    val query = MutableStateFlow("")
    val sort = MutableStateFlow(prefs.sort)
    val ascending = MutableStateFlow(prefs.ascending)
    val filter = MutableStateFlow(prefs.filter)
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
        val filter: LibraryFilter,
    )

    val cards: StateFlow<List<LibraryCard>> =
        combine(allCards, query, sort, ascending, filter, ::FilterInputs)
            .combine(appPreferences.titleLanguage) { inputs, titleLanguage ->
                var list = inputs.cards
                if (inputs.query.isNotBlank()) list = list.filter { it.title.contains(inputs.query, ignoreCase = true) }
                when (inputs.filter) {
                    LibraryFilter.SHOW_ALL -> {}
                    LibraryFilter.HIDE_READ -> list = list.filter { it.unreadCount > 0 }
                    LibraryFilter.HIDE_MATCHED -> list = list.filter { it.externalId == null }
                }
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
            // savedLocalRoot() holds a raw SAF root URI for a LOCAL source, or an SmbConfig
            // blob for an SMB one — resolveScanRoot() picks the right locator to actually
            // scan/canAccess out of either. Cold start with a saved SMB config rebuilds the
            // connection before the canAccess check, so re-grant detection runs against the
            // right backend instead of whatever SafMangaSource MainActivity defaulted to.
            val type = repository.savedSourceType()
            val configBlob = repository.savedLocalRoot()
            if (type == "SMB" && configBlob != null) {
                reconfigureSmbSource(configBlob, smbSourceFactory?.loadPassword() ?: "")
            }
            val scanRoot = resolveScanRoot(type, configBlob)
            _canRescan.value = scanRoot != null
            if (scanRoot != null && !source.canAccess(scanRoot)) _needsReGrant.value = true
        }
        scope.launch { sort.collect { prefs.sort = it } }
        scope.launch { ascending.collect { prefs.ascending = it } }
        scope.launch { filter.collect { prefs.filter = it } }
    }

    fun onFolderPicked(rootLocator: String, displayName: String) {
        scope.launch {
            repository.saveLocalRoot(rootLocator, displayName)
            (source as? ConfigurableMangaSource)?.reconfigure(localSource)
            _canRescan.value = true
            _needsReGrant.value = false
            runScan(rootLocator)
        }
    }

    /** Mirrors [onFolderPicked] for an SMB share (PLAN.md §6), called from the connect
     * dialog's own coroutine scope so it can show a real error instead of firing blind.
     * Validates the candidate connection *before* persisting/reconfiguring anything, so a
     * bad host/share/credentials can't clobber a working source or half-commit — returns
     * an error message on failure, or null as soon as the connection itself succeeds. The
     * scan is deliberately fire-and-forget (its own `scope.launch`, not awaited here) so the
     * dialog can close immediately on a successful connection instead of sitting open through
     * the whole scan — same "Scanning..." top-bar indicator picks it up either way. */
    suspend fun connectSmb(host: String, share: String, username: String, password: String, rootPath: String, displayName: String): String? {
        val factory = smbSourceFactory ?: return "SMB isn't supported on this platform"
        val candidate = factory.build(host, share, username, password)
        if (!candidate.canAccess(rootPath)) return "Couldn't connect — check host, share, and credentials."
        repository.saveSmbSource(SmbConfig(host, share, username, rootPath).toBlob(), displayName)
        factory.savePassword(password)
        (source as? ConfigurableMangaSource)?.reconfigure(candidate)
        _canRescan.value = true
        _needsReGrant.value = false
        scope.launch { runScan(rootPath) }
        return null
    }

    /** Settings -> Reset library (PLAN.md §7.1): wipes the DB, the cached cover/banner files,
     * the image loader's own cache, and any saved SMB credentials, then reverts to the local SAF
     * source — the app ends up back in its pre-first-scan "no source configured" state. */
    fun resetLibrary() {
        scope.launch {
            repository.resetLibrary()
            clearDirectory(coversDir)
            clearImageCache()
            smbSourceFactory?.clearPassword()
            (source as? ConfigurableMangaSource)?.reconfigure(localSource)
            _canRescan.value = false
            _needsReGrant.value = false
            exitSelectionMode()
            query.value = ""
        }
    }

    fun rescan() {
        scope.launch {
            val type = repository.savedSourceType()
            val configBlob = repository.savedLocalRoot()
            resolveScanRoot(type, configBlob)?.let { runScan(it) }
        }
    }

    private fun resolveScanRoot(type: String?, configBlob: String?): String? {
        if (configBlob == null) return null
        return if (type == "SMB") SmbConfig.fromBlob(configBlob)?.rootPath ?: "" else configBlob
    }

    private fun reconfigureSmbSource(configBlob: String, password: String) {
        val factory = smbSourceFactory ?: return
        val config = SmbConfig.fromBlob(configBlob) ?: return
        (source as? ConfigurableMangaSource)?.reconfigure(
            factory.build(config.host, config.share, config.username, password),
        )
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
            repository.markSeriesProgress(ids, completed, appPreferences.deviceId)
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
        // [_enrichProgress] lets the UI show it's still fetching well after the scan is done.
        scope.launch {
            try {
                enricher.enrichPending { done, total -> _enrichProgress.value = EnrichProgress(done, total) }
            } finally {
                _enrichProgress.value = null
            }
        }
    }
}

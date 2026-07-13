package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.ChapterCard
import com.oliver.heyme.mangazuki.core.data.LibraryCard
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.data.RecentChapterCard
import com.oliver.heyme.mangazuki.core.domain.normalizeSortTitle
import com.oliver.heyme.mangazuki.core.scanner.LibraryScanner
import com.oliver.heyme.mangazuki.core.source.MangaSource
import com.oliver.heyme.mangazuki.core.source.SourceEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [directoriesScanned] ticks up on every folder the scanner lists, well before any series is
 * fully processed (which is what [seriesFound]/[chaptersFound] wait on) -- a big first series can
 * otherwise leave the UI showing "0 series, 0 chapters" for a long time despite real progress
 * happening (PLAN.md §5). */
data class ScanProgress(val seriesFound: Int, val chaptersFound: Int, val directoriesScanned: Int = 0)

/** How many series [LibraryViewModel.inProgress] prefetches a resume chapter for -- "Your Page"
 * dashboard's "Jump back in" section never shows more than this. Must be >= the largest card
 * count [YourPageContent] can request (2 rows x 2 columns in portrait, or up to 4 columns x 1 row
 * in landscape, so 4) -- a series pulled into the section without a prefetched resume chapter
 * renders as a blank slot instead of falling through to "On your shelf". */
private const val JUMP_BACK_IN_COUNT = 4

/** How many rows [LibraryViewModel.recentChapters] pulls from the DB -- the dashboard's "Fresh
 * chapters" grid shows a subset of this. Wide landscape windows can fit noticeably more than a
 * portrait row's worth of covers per row (see [YourPageContent]'s `coverGridColumns`), so this
 * needs real headroom above a single portrait row (6) rather than just matching it. */
private const val FRESH_CHAPTERS_LIMIT = 24L

/** [done]/[total] series processed by the current [MetadataEnricher.enrichPending] pass
 * (PLAN.md §9.2) — "processed" includes matched, checked-no-match, and failed alike. */
data class EnrichProgress(val done: Int, val total: Int)

enum class SortMode { NAME, RECENTLY_ADDED, RECENTLY_READ, RELEASE_START }

/** Library filter (PLAN.md §7.1): "Hide matched" is the AniList-match counterpart of "Hide
 * read" — hides series that already have an `external_id`, for focusing on what Fix Metadata
 * still needs to look at (PLAN.md §9.1). "Show in progress" narrows to partially-read series —
 * the same definition the "Your Page" dashboard's [LibraryViewModel.inProgress] uses. "Show
 * favorites" narrows to hearted series (PLAN.md §10 favorites). */
enum class LibraryFilter { SHOW_ALL, SHOW_IN_PROGRESS, SHOW_FAVORITES, HIDE_READ, HIDE_MATCHED }

class LibraryViewModel(
    private val repository: LibraryRepository,
    scanner: LibraryScanner,
    private val source: MangaSource,
    /** The original local (SAF) source instance — reused as-is when switching back to a
     * local folder, since it's just a stateless wrapper around the platform Context. */
    private val localSource: MangaSource,
    /** Null on platforms without SMB support yet (PLAN.md §6) — [onSmbConnect] no-ops. */
    private val smbSourceFactory: SmbSourceFactory?,
    /** Null on platforms without OneDrive support yet (PLAN.md §6.3) — same seam pattern. */
    private val oneDriveSourceFactory: OneDriveSourceFactory? = null,
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
    /** Debounced cloud-sync trigger (PLAN.md §10) — see [AppGraph.requestSync]. */
    private val requestSync: () -> Unit = {},
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

    /** Multi-select series for bulk mark read/unread (PLAN.md §7.5). */
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Which Library screen tab is showing (PLAN.md) -- seeded once here from the configured
     * Start Screen setting, then plain in-memory state for the rest of this process's lifetime.
     * That single seed point is deliberate (2026-07-13): a background-and-resume that never
     * kills the process should keep whichever tab was open, which this already gets for free
     * simply by being a plain `StateFlow` on a `LibraryViewModel` instance that `AppGraph` (and
     * so `MainActivity`) holds onto unchanged the whole time the process is alive -- no explicit
     * "was this recent" logic needed. A genuine process kill, on the other hand, must always
     * re-seed from the Start Screen setting, no matter how recently the app was open: this
     * ViewModel gets rebuilt fresh in that case (via a new `AppGraph` in `MainActivity.onCreate`),
     * re-running this exact seed with nothing else in the way. Earlier versions of this seed
     * also consulted a persisted "last active tab" as a fallback, on the mistaken assumption that
     * a background trip could kill the process without Android delivering a fresh Activity
     * instance for it to reseed from -- in testing that never actually happened once this moved
     * off `rememberSaveable` (see [MangaShelfGrid]'s own history note), and the persisted
     * fallback just meant a deliberate setting change could still lose to a stale recent tab. */
    val activeTab = MutableStateFlow(appPreferences.startScreen.value)

    fun setActiveTab(tab: StartScreen) {
        activeTab.value = tab
    }

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
                    // Same "partially read" definition as [inProgress] (Your Page's feed).
                    LibraryFilter.SHOW_IN_PROGRESS -> list = list.filter { it.isInProgress }
                    LibraryFilter.SHOW_FAVORITES -> list = list.filter { it.favorite }
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

    /** Partially-read series (some progress, not finished), most-recently-read first -- the
     * "Your Page" dashboard's own view of the library (PLAN.md), independent of the Library
     * tab's own search/sort/filter state. See [LibraryCard.isInProgress] for the definition
     * (shared with the "Show in progress" filter and the shelf card's CONTINUE badge). */
    val inProgress: StateFlow<List<LibraryCard>> = allCards.map { list ->
        list.filter { it.isInProgress }
            .sortedByDescending { it.latestRead ?: 0L }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hearted series, most-recently-hearted first -- the "Your Page" dashboard's Favorites
     * shelf (PLAN.md §10 favorites), independent of the Library tab's filter state. */
    val favorites: StateFlow<List<LibraryCard>> = allCards.map { list ->
        list.filter { it.favorite }
            .sortedByDescending { it.favoriteUpdatedAt ?: 0L }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Most-recently-added chapters across the whole library -- the dashboard's "Fresh chapters"
     * feed (PLAN.md). */
    val recentChapters: StateFlow<List<RecentChapterCard>> =
        repository.observeRecentChapters(FRESH_CHAPTERS_LIMIT)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The chapter each of [inProgress]'s top few series would resume into, keyed by series id
     * -- one-shot lookups (`nextUnreadChapter`), refreshed whenever the in-progress list itself
     * changes (a new read, a rescan, etc.) rather than on every unrelated write. */
    private val _resumeChapters = MutableStateFlow<Map<String, ChapterCard>>(emptyMap())
    val resumeChapters: StateFlow<Map<String, ChapterCard>> = _resumeChapters

    init {
        scope.launch {
            // savedLocalRoot() holds a raw SAF root URI for a LOCAL source, or an SmbConfig/
            // OneDriveConfig blob otherwise — resolveScanRoot() picks the right locator to
            // actually scan/canAccess out of any of them. Cold start with a saved SMB/OneDrive
            // config rebuilds that backend before the canAccess check, so re-grant detection
            // runs against the right source instead of whatever SafMangaSource MainActivity
            // defaulted to.
            val type = repository.savedSourceType()
            val configBlob = repository.savedLocalRoot()
            if (type == "SMB" && configBlob != null) {
                reconfigureSmbSource(configBlob, smbSourceFactory?.loadPassword() ?: "")
            }
            if (type == "ONEDRIVE") {
                reconfigureOneDriveSource()
            }
            val scanRoot = resolveScanRoot(type, configBlob)
            _canRescan.value = scanRoot != null
            if (scanRoot != null && !source.canAccess(scanRoot)) _needsReGrant.value = true
        }
        scope.launch { sort.collect { prefs.sort = it } }
        scope.launch { ascending.collect { prefs.ascending = it } }
        scope.launch { filter.collect { prefs.filter = it } }
        scope.launch {
            inProgress.collect { list ->
                _resumeChapters.value = list.take(JUMP_BACK_IN_COUNT)
                    .mapNotNull { card -> repository.nextUnreadChapter(card.id)?.let { card.id to it } }
                    .toMap()
            }
        }
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

    /** Whether the OneDrive connect dialog can skip its sign-in step (PLAN.md §6.3). */
    fun isOneDriveSignedIn(): Boolean = oneDriveSourceFactory?.isSignedIn() == true

    /** Candidate source backing one folder-browse session (PLAN.md §6.3) — built lazily on the
     * first listing, discarded when a browse commits or the app process ends. Deliberately NOT
     * the live [source]: nothing is persisted or reconfigured until [connectOneDrive]. */
    private var oneDriveCandidate: MangaSource? = null

    /** One directory level of the signed-in OneDrive, folders only — drives the connect
     * dialog's folder browser. [Result] rather than throwing so the dialog can show a retry
     * affordance on network failures without try/catch in composition. */
    suspend fun listOneDriveFolders(path: String): Result<List<SourceEntry>> {
        val factory = oneDriveSourceFactory ?: return Result.failure(IllegalStateException("OneDrive isn't supported on this platform"))
        val candidate = oneDriveCandidate ?: factory.build().also { oneDriveCandidate = it }
        return runCatching { candidate.list(path).filter { it.isDirectory } }
    }

    /** Mirrors [connectSmb] for OneDrive (PLAN.md §6.3): validates the chosen root against the
     * candidate source *before* persisting/reconfiguring anything (belt-and-braces — the user
     * just browsed there, but the token could have expired mid-browse), then persists, swaps the
     * live source, and fires the scan without holding the dialog open. */
    suspend fun connectOneDrive(rootPath: String, displayName: String): String? {
        val factory = oneDriveSourceFactory ?: return "OneDrive isn't supported on this platform"
        val candidate = oneDriveCandidate ?: factory.build()
        if (!candidate.canAccess(rootPath)) return "Couldn't access this folder on OneDrive."
        repository.saveOneDriveSource(OneDriveConfig(rootPath).toBlob(), displayName)
        (source as? ConfigurableMangaSource)?.reconfigure(candidate)
        oneDriveCandidate = null
        _canRescan.value = true
        _needsReGrant.value = false
        scope.launch { runScan(rootPath) }
        return null
    }

    /** Settings -> Reset library (PLAN.md §7.1): wipes the DB, the cached cover/banner files,
     * the image loader's own cache, and any saved SMB/OneDrive credentials, then reverts to the
     * local SAF source — the app ends up back in its pre-first-scan "no source configured" state. */
    fun resetLibrary() {
        scope.launch {
            repository.resetLibrary()
            clearDirectory(coversDir)
            clearImageCache()
            smbSourceFactory?.clearPassword()
            oneDriveSourceFactory?.signOut()
            oneDriveCandidate = null
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
        return when (type) {
            "SMB" -> SmbConfig.fromBlob(configBlob)?.rootPath ?: ""
            // A OneDrive root of "" (the whole drive) is legitimate — the non-null blob is
            // what distinguishes "configured at the drive root" from "not configured".
            "ONEDRIVE" -> OneDriveConfig.fromBlob(configBlob).rootPath
            else -> configBlob
        }
    }

    private fun reconfigureSmbSource(configBlob: String, password: String) {
        val factory = smbSourceFactory ?: return
        val config = SmbConfig.fromBlob(configBlob) ?: return
        (source as? ConfigurableMangaSource)?.reconfigure(
            factory.build(config.host, config.share, config.username, password),
        )
    }

    private fun reconfigureOneDriveSource() {
        val factory = oneDriveSourceFactory ?: return
        (source as? ConfigurableMangaSource)?.reconfigure(factory.build())
    }

    fun toggleDirection() { ascending.value = !ascending.value }

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
            requestSync()
            exitSelectionMode()
        }
    }

    private suspend fun runScan(rootLocator: String) {
        if (_progress.value != null) return
        if (!source.canAccess(rootLocator)) { _needsReGrant.value = true; return }
        _progress.value = ScanProgress(0, 0)
        try {
            // Cancels a still-running enrichment pass before waiting on libraryWriteMutex --
            // wherever it was started from, even a completely different coroutine scope like
            // ScanWorker's own background pass (PLAN.md §9.2, 2026-07-06). See LibrarySyncer.sync.
            syncer.sync(rootLocator) { progress -> _progress.value = progress }
        } finally {
            _progress.value = null
        }
        // A scan can recreate chapters with a clean `reading_progress` (e.g. after Settings ->
        // Reset library, or a first scan on a new install) -- deterministic IDs (§5) mean a synced
        // remote record for one of them can still apply, but only once a cloud sync actually runs.
        // Without this, nothing shows as read until the next unrelated trigger (sign-in, a manual
        // mark-as-read, or the periodic 6h worker) happens to fire one (PLAN.md §10).
        requestSync()
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

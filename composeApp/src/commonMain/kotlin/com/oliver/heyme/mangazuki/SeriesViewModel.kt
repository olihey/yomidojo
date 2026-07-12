package com.oliver.heyme.mangazuki

import com.oliver.heyme.mangazuki.core.data.ChapterCard
import com.oliver.heyme.mangazuki.core.data.LibraryRepository
import com.oliver.heyme.mangazuki.core.domain.Chapter as DomainChapter
import com.oliver.heyme.mangazuki.core.domain.ChapterFormat
import com.oliver.heyme.mangazuki.core.domain.Series as DomainSeries
import com.oliver.heyme.mangazuki.core.metadata.RemoteWork
import com.oliver.heyme.mangazuki.core.reader.pageProviderFor
import com.oliver.heyme.mangazuki.core.source.MangaSource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

class SeriesViewModel(
    private val repository: LibraryRepository,
    private val source: MangaSource,
    val seriesId: String,
    private val providers: MetadataProviders,
    private val appPreferences: AppPreferences,
    private val coverClient: HttpClient,
    private val coversDir: String,
    /** Debounced cloud-sync trigger (PLAN.md §10) — see [AppGraph.requestSync]. */
    private val requestSync: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    val series: StateFlow<DomainSeries?> =
        repository.observeSeries(seriesId).stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ascending (Chapter 1 first); volume/number order, flat grid regardless of volume (PLAN.md §7.3). */
    val chapters: StateFlow<List<ChapterCard>> =
        repository.observeChapters(seriesId).stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Multi-select chapters for bulk read/unread (PLAN.md §7.5). */
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Fix Metadata (PLAN.md §9.1): a keyword search prefilled with the current title, listing
     * candidates from [metadataSearchProvider]; picking one rebinds external_id and re-enriches. */
    val metadataSearchOpen = MutableStateFlow(false)
    val metadataSearchQuery = MutableStateFlow("")
    val metadataSearchResults = MutableStateFlow<List<RemoteWork>>(emptyList())
    val metadataSearchLoading = MutableStateFlow(false)

    /** Defaults to the global setting every time the dialog opens (PLAN.md §9.3) — switching
     * it here is a one-time override for this lookup only, never persisted as the new default. */
    val metadataSearchProvider = MutableStateFlow(appPreferences.metadataProvider.value)

    /** Chapter ids already counted or currently being counted, so [chapters] re-emitting after a
     * write-back doesn't re-trigger the same chapter (PLAN.md §9: covers/counts are on-demand). */
    private val pageCountAttempted = mutableSetOf<String>()

    /** Caps how many chapters get counted at once — counting a CBZ means reading the whole
     * archive, and a series can have hundreds of chapters missing a count after a fresh scan. */
    private val pageCountLimiter = Semaphore(4)

    /** Counted results land here instead of writing straight to the DB per chapter — a series
     * with hundreds of chapters missing a count would otherwise re-run observeChapters' reactive
     * query (and recompose the whole grid) once per chapter as each finished. */
    private val countedResults = Channel<Pair<String, Int>>(Channel.UNLIMITED)

    init {
        scope.launch { batchWriteCounts() }
        scope.launch {
            chapters.collect { list ->
                // The read-percentage ring only ever renders for a chapter that's in progress
                // (started, not finished) — counting anything else (the vast majority of a
                // freshly-scanned series: untouched chapters, or ones just marked read wholesale)
                // is pure waste that was the real cost behind "series screen takes forever to
                // open": reading through hundreds of whole CBZs nobody has even started yet.
                list.filter { !it.completed && it.lastPageIndex > 0 && it.pageCount == null && it.id !in pageCountAttempted }
                    .forEach { chapter ->
                        pageCountAttempted += chapter.id
                        scope.launch { countPages(chapter) }
                    }
            }
        }
    }

    fun toggleRead(chapter: ChapterCard) {
        scope.launch {
            val nowCompleted = !chapter.completed
            val lastPage = if (nowCompleted) (chapter.pageCount ?: 1) - 1 else 0
            repository.markProgress(chapter.id, lastPage.coerceAtLeast(0), nowCompleted, appPreferences.deviceId)
            requestSync()
        }
    }

    /** The detail screen's heart (PLAN.md §10 favorites) -- same write-then-request-sync shape
     * as [toggleRead]; the observed [series] flow updates the button state reactively. */
    fun toggleFavorite() {
        val current = series.value ?: return
        scope.launch {
            repository.setFavorite(seriesId, !current.favorite, appPreferences.deviceId)
            requestSync()
        }
    }

    fun openMetadataSearch() {
        metadataSearchQuery.value = series.value?.title ?: ""
        metadataSearchResults.value = emptyList()
        // Reset to the current global default every time — never carries a stale override
        // from a previous series or an earlier dialog session (PLAN.md §9.3).
        metadataSearchProvider.value = appPreferences.metadataProvider.value
        metadataSearchOpen.value = true
        searchMetadata()
    }

    fun dismissMetadataSearch() {
        metadataSearchOpen.value = false
    }

    /** Just edits the field — the search only fires from [searchMetadata], not per keystroke. */
    fun updateMetadataQuery(query: String) {
        metadataSearchQuery.value = query
    }

    fun clearMetadataQuery() {
        metadataSearchQuery.value = ""
        metadataSearchResults.value = emptyList()
    }

    /** One-time override for this lookup only (PLAN.md §9.3) — never touches the global
     * setting. Re-runs the search immediately so switching provider mid-dialog refreshes results. */
    fun setMetadataSearchProvider(choice: MetadataProviderChoice) {
        metadataSearchProvider.value = choice
        searchMetadata()
    }

    fun searchMetadata() {
        val query = metadataSearchQuery.value
        if (query.isBlank()) { metadataSearchResults.value = emptyList(); return }
        scope.launch {
            metadataSearchLoading.value = true
            try {
                metadataSearchResults.value = providers.get(metadataSearchProvider.value).search(query)
            } catch (t: Throwable) {
                metadataSearchResults.value = emptyList()
            } finally {
                metadataSearchLoading.value = false
            }
        }
    }

    /** Rebinds external_id and re-enriches from the chosen candidate (PLAN.md §9.1) —
     * the same fields the background pipeline would have written, just user-confirmed. Also
     * records a metadata alias (PLAN.md §10) — this series' raw scanned title, exactly as it
     * stood before this fix, paired with the provider/id just confirmed — so cross-device sync
     * can bridge other devices that haven't matched (or scanned under a different raw title)
     * this same series yet. */
    fun applyMetadataMatch(work: RemoteWork) {
        scope.launch {
            metadataSearchLoading.value = true
            try {
                val details = providers.get(metadataSearchProvider.value).details(work.externalId)
                val coverPath = downloadCover(coverClient, coversDir, details.externalId, details.coverUrl)
                val bannerPath = downloadBanner(coverClient, coversDir, details.externalId, details.bannerUrl)
                val oldTitle = series.value?.sortTitle
                repository.applyMetadata(seriesId, details, coverPath, bannerPath)
                if (oldTitle != null) {
                    repository.recordMetadataAlias(oldTitle, details.providerId, details.externalId, appPreferences.deviceId)
                }
                requestSync()
                metadataSearchOpen.value = false
            } catch (t: Throwable) {
                // Leave the dialog open with its current results — the user can retry.
            } finally {
                metadataSearchLoading.value = false
            }
        }
    }

    fun enterSelectionMode(chapterId: String) {
        selectionMode.value = true
        selectedIds.value = setOf(chapterId)
    }

    fun toggleSelected(chapterId: String) {
        selectedIds.value = if (chapterId in selectedIds.value) selectedIds.value - chapterId else selectedIds.value + chapterId
        if (selectedIds.value.isEmpty()) selectionMode.value = false
    }

    fun selectAll() { selectedIds.value = chapters.value.map { it.id }.toSet() }
    fun selectNone() { selectedIds.value = emptySet() }

    fun exitSelectionMode() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    fun markSelectedRead(completed: Boolean) {
        val entries = chapters.value.filter { it.id in selectedIds.value }.map { it.id to it.pageCount }
        scope.launch {
            repository.markChaptersProgress(entries, completed, appPreferences.deviceId)
            requestSync()
            exitSelectionMode()
        }
    }

    /** Same PageProvider the reader uses, just to read `.pageCount` — cheap (entry names/dir
     * listing only, no image bytes) and reuses the existing CBZ/image-dir abstraction directly.
     * PDFs are deliberately excluded: counting a PDF's pages means materializing the whole file
     * locally first (PLAN.md §16) — a full download on SMB/OneDrive, nothing like the KB-sized
     * central-directory range reads a CBZ costs here. The reader persists a PDF's count on its
     * first open instead (see `ReaderViewModel`). */
    private suspend fun countPages(chapter: ChapterCard) {
        if (chapter.format == ChapterFormat.PDF.name) return
        val domainChapter = DomainChapter(
            id = chapter.id,
            seriesId = chapter.seriesId,
            sourceId = chapter.sourceId,
            locator = chapter.locator,
            format = ChapterFormat.valueOf(chapter.format),
            displayName = chapter.displayName,
            volume = chapter.volume,
            number = chapter.number,
            pageCount = null,
            size = chapter.size,
            dateAdded = 0L,
        )
        try {
            pageCountLimiter.withPermit {
                val provider = pageProviderFor(domainChapter, source)
                val count = provider.pageCount
                provider.close()
                if (count > 0) countedResults.send(chapter.id to count)
            }
        } catch (t: Throwable) {
            // Best-effort — leave pageCount null; the read-percentage overlay just won't show.
        }
    }

    /** Drains [countedResults] in small batches (one DB transaction each) instead of one write
     * per chapter, so the tiles still update live without thrashing the reactive chapters query. */
    private suspend fun batchWriteCounts() {
        while (true) {
            val batch = mutableListOf(countedResults.receive())
            withTimeoutOrNull(200) {
                while (true) batch += countedResults.receive()
            }
            repository.setChapterPageCounts(batch)
        }
    }
}

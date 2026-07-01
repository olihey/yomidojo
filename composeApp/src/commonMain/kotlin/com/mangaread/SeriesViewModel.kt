package com.mangaread

import com.mangaread.core.data.ChapterCard
import com.mangaread.core.data.LibraryRepository
import com.mangaread.core.domain.Series as DomainSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SeriesViewModel(
    private val repository: LibraryRepository,
    val seriesId: String,
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

    fun toggleRead(chapter: ChapterCard) {
        scope.launch {
            val nowCompleted = !chapter.completed
            val lastPage = if (nowCompleted) (chapter.pageCount ?: 1) - 1 else 0
            repository.markProgress(chapter.id, lastPage.coerceAtLeast(0), nowCompleted)
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
            repository.markChaptersProgress(entries, completed)
            exitSelectionMode()
        }
    }
}

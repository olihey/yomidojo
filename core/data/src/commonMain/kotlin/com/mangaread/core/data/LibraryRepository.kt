package com.mangaread.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.mangaread.core.data.db.MangaDatabase
import com.mangaread.core.domain.Chapter as DomainChapter
import com.mangaread.core.domain.ReadingDirection
import com.mangaread.core.domain.Series as DomainSeries
import com.mangaread.core.domain.ioDispatcher
import com.mangaread.core.domain.nowEpochMillis
import com.mangaread.core.metadata.RemoteWorkDetails
import com.mangaread.core.data.db.Series as SeriesRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LibraryRepository(db: MangaDatabase) {

    private val q = db.schemaQueries

    private companion object { const val LOCAL_SOURCE_ID = "local" }

    fun observeSeries(): Flow<List<DomainSeries>> =
        q.selectAllSeries().asFlow().mapToList(ioDispatcher).map { rows -> rows.map(::toDomain) }

    /** Library cards (series + counts), reactive. Sorting/filtering happens in the VM. */
    fun observeLibrary(): Flow<List<LibraryCard>> =
        q.selectLibrary().asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map { r ->
                LibraryCard(
                    id = r.id,
                    title = r.title,
                    sortTitle = r.sort_title,
                    author = r.author,
                    coverPath = r.cover_path,
                    chapterCount = r.chapter_count.toInt(),
                    unreadCount = (r.chapter_count - r.read_count).toInt(),
                    latestChapterAdded = r.latest_chapter_added ?: r.date_added,
                    latestRead = r.latest_read,
                    startYear = r.start_year?.toInt(),
                    externalId = r.external_id,
                    metadataCheckedAt = r.metadata_checked_at,
                    titleRomaji = r.title_romaji,
                    titleEnglish = r.title_english,
                    titleNative = r.title_native,
                    coverModel = coverModel(r.cover_path, r.cover_format, r.cover_locator),
                )
            }
        }

    fun observeSeries(seriesId: String): Flow<DomainSeries?> =
        q.selectSeriesById(seriesId).asFlow().mapToOneOrNull(ioDispatcher).map { it?.let(::toDomain) }

    /** Chapters for the series screen, grouped by volume in the VM (PLAN.md §7.3). */
    fun observeChapters(seriesId: String): Flow<List<ChapterCard>> =
        q.selectChaptersForSeriesWithProgress(seriesId).asFlow().mapToList(ioDispatcher).map { rows ->
            rows.map { r ->
                ChapterCard(
                    id = r.id,
                    seriesId = r.series_id,
                    sourceId = r.source_id,
                    locator = r.locator,
                    format = r.format,
                    displayName = r.display_name,
                    volume = r.volume,
                    number = r.number,
                    pageCount = r.page_count?.toInt(),
                    lastPageIndex = r.last_page_index?.toInt() ?: 0,
                    completed = r.completed == 1L,
                    coverModel = coverModel(r.cover_path, r.format, r.locator),
                )
            }
        }

    /**
     * Records real page counts once counted on demand by the series screen (not at scan time),
     * feeding the read-percentage overlay (PLAN.md §7.2). Batched in one transaction so a series
     * with many chapters missing a count doesn't re-trigger observeChapters' reactive query (and
     * a full-grid recomposition) once per chapter — SQLDelight coalesces query invalidation to
     * fire once per transaction, not once per statement.
     */
    suspend fun setChapterPageCounts(counts: List<Pair<String, Int>>) = withContext(ioDispatcher) {
        q.transaction {
            counts.forEach { (chapterId, pageCount) -> q.updateChapterPageCount(pageCount.toLong(), chapterId) }
        }
    }

    /** Persist reading progress; drives resume, the unread badge, and the recently-read sort. */
    suspend fun markProgress(chapterId: String, lastPageIndex: Int, completed: Boolean) =
        withContext(ioDispatcher) {
            q.upsertProgress(
                chapter_id = chapterId,
                last_page_index = lastPageIndex.toLong(),
                completed = if (completed) 1 else 0,
                updated_at = nowEpochMillis(),
                device_id = null,
            )
        }

    /** Bulk mark/unmark specific chapters (PLAN.md §7.5 series-screen selection). */
    suspend fun markChaptersProgress(chapters: List<Pair<String, Int?>>, completed: Boolean) =
        withContext(ioDispatcher) {
            val now = nowEpochMillis()
            q.transaction {
                chapters.forEach { (chapterId, pageCount) ->
                    val lastPage = if (completed) ((pageCount ?: 1) - 1).coerceAtLeast(0) else 0
                    q.upsertProgress(chapterId, lastPage.toLong(), if (completed) 1 else 0, now, null)
                }
            }
        }

    /** Bulk mark/unmark every chapter of the given series (PLAN.md §7.5 library selection). */
    suspend fun markSeriesProgress(seriesIds: List<String>, completed: Boolean) = withContext(ioDispatcher) {
        val now = nowEpochMillis()
        q.transaction {
            seriesIds.forEach { seriesId ->
                q.selectChaptersForSeries(seriesId).executeAsList().forEach { c ->
                    val lastPage = if (completed) ((c.page_count ?: 1L) - 1).coerceAtLeast(0) else 0L
                    q.upsertProgress(c.id, lastPage, if (completed) 1 else 0, now, null)
                }
            }
        }
    }

    /** Persist the granted library root so it's remembered across restarts (PLAN.md §5 source table). */
    suspend fun saveLocalRoot(rootLocator: String, displayName: String) = withContext(ioDispatcher) {
        q.upsertSource(
            id = LOCAL_SOURCE_ID,
            type = "LOCAL",
            display_name = displayName,
            config_json = rootLocator,
            sync_token = null,
        )
    }

    suspend fun savedLocalRoot(): String? = withContext(ioDispatcher) {
        q.selectSourceRoot(LOCAL_SOURCE_ID).executeAsOneOrNull()
    }

    /**
     * Persist one series and its chapters in a single transaction. Idempotent: upserts on
     * deterministic IDs, so re-scans reconcile rather than duplicate (PLAN.md §5). Called once
     * per series during a scan so the library fills in incrementally.
     */
    suspend fun persistSeries(series: DomainSeries, chapters: List<DomainChapter>) =
        withContext(ioDispatcher) {
            q.transaction {
                q.upsertSeries(
                    id = series.id,
                    title = series.title,
                    sort_title = series.sortTitle,
                    author = series.author,
                    description = series.description,
                    cover_path = series.coverPath,
                    start_year = series.startYear?.toLong(),
                    reading_direction = series.readingDirection?.name,
                    external_id = series.externalId,
                    date_added = series.dateAdded,
                    last_scanned = series.lastScanned,
                )
                chapters.forEach { c ->
                    q.upsertChapter(
                        id = c.id,
                        series_id = c.seriesId,
                        source_id = c.sourceId,
                        locator = c.locator,
                        format = c.format.name,
                        display_name = c.displayName,
                        volume = c.volume,
                        number = c.number,
                        page_count = c.pageCount?.toLong(),
                        size = c.size,
                        change_token = c.changeToken,
                        date_added = c.dateAdded,
                    )
                }
                // Prune chapters that disappeared from this series on disk.
                q.deleteChaptersForSeriesNotIn(series.id, chapters.map { it.id })
            }
        }

    /**
     * Remove series (and, via cascade, their chapters/progress) not touched by the scan that
     * stamped [scannedAt] as last_scanned. Call ONLY after a scan completes successfully.
     */
    suspend fun deleteSeriesNotScannedAt(scannedAt: Long) = withContext(ioDispatcher) {
        q.deleteSeriesNotScannedAt(scannedAt)
    }

    /** (id, title) pairs still missing an AniList match — the enrichment pipeline's work
     * queue (PLAN.md §9.2). A one-shot snapshot, not reactive: the pipeline pulls a fresh
     * batch each pass rather than reacting to every intermediate write mid-run. */
    suspend fun unmatchedSeries(): List<Pair<String, String>> = withContext(ioDispatcher) {
        q.selectUnmatchedSeries().executeAsList().map { it.id to it.title }
    }

    /** Writes AniList-derived fields for one series — used by both the background
     * enrichment pipeline and the user-facing Fix Metadata re-match (PLAN.md §9, §9.1).
     * Deliberately its own query, not routed through [persistSeries]/upsertSeries, whose
     * ON CONFLICT clause leaves these columns untouched on every rescan by design. */
    suspend fun applyMetadata(seriesId: String, details: RemoteWorkDetails, coverPath: String?) =
        withContext(ioDispatcher) {
            q.updateSeriesMetadata(
                author = details.author,
                description = details.description,
                cover_path = coverPath,
                start_year = details.startYear?.toLong(),
                external_id = details.externalId,
                title_romaji = details.titleRomaji,
                title_english = details.titleEnglish,
                title_native = details.titleNative,
                id = seriesId,
            )
        }

    /** Stamps that enrichment ran and found nothing good enough (PLAN.md §9.2) — the library
     * badge shows "✕" for these instead of "?" (never checked yet). */
    suspend fun markMetadataChecked(seriesId: String) = withContext(ioDispatcher) {
        q.markMetadataChecked(nowEpochMillis(), seriesId)
    }

    private fun coverModel(coverPath: String?, format: String?, locator: String?): String? = when {
        coverPath != null -> coverPath          // a real cached cover (Phase 3) wins
        locator == null -> null
        format == "CBZ" -> "cbz:$locator"
        format == "IMAGE_DIR" -> "imgdir:$locator"
        else -> null
    }

    private fun toDomain(r: SeriesRow) = DomainSeries(
        id = r.id,
        title = r.title,
        sortTitle = r.sort_title,
        author = r.author,
        description = r.description,
        coverPath = r.cover_path,
        startYear = r.start_year?.toInt(),
        readingDirection = r.reading_direction?.let { ReadingDirection.valueOf(it) },
        externalId = r.external_id,
        dateAdded = r.date_added,
        lastScanned = r.last_scanned,
        titleRomaji = r.title_romaji,
        titleEnglish = r.title_english,
        titleNative = r.title_native,
    )
}

package com.oliver.heyme.mangazuki.core.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase
import com.oliver.heyme.mangazuki.core.domain.Chapter as DomainChapter
import com.oliver.heyme.mangazuki.core.domain.ReadingDirection
import com.oliver.heyme.mangazuki.core.domain.Series as DomainSeries
import com.oliver.heyme.mangazuki.core.domain.ioDispatcher
import com.oliver.heyme.mangazuki.core.domain.nowEpochMillis
import com.oliver.heyme.mangazuki.core.domain.SyncProgressRow
import com.oliver.heyme.mangazuki.core.metadata.RemoteWorkDetails
import com.oliver.heyme.mangazuki.core.data.db.Series as SeriesRow
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
                    status = r.status,
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
                    size = r.size,
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

    /** Persist reading progress; drives resume, the unread badge, and the recently-read sort.
     * [deviceId] tags the write for cross-device sync's merge tiebreak (PLAN.md §10) — never
     * used to decide *whether* to write, only carried through for a later merge to read. */
    suspend fun markProgress(chapterId: String, lastPageIndex: Int, completed: Boolean, deviceId: String) =
        withContext(ioDispatcher) {
            q.upsertProgress(
                chapter_id = chapterId,
                last_page_index = lastPageIndex.toLong(),
                completed = if (completed) 1 else 0,
                updated_at = nowEpochMillis(),
                device_id = deviceId,
            )
        }

    /** Bulk mark/unmark specific chapters (PLAN.md §7.5 series-screen selection). */
    suspend fun markChaptersProgress(chapters: List<Pair<String, Int?>>, completed: Boolean, deviceId: String) =
        withContext(ioDispatcher) {
            val now = nowEpochMillis()
            q.transaction {
                chapters.forEach { (chapterId, pageCount) ->
                    val lastPage = if (completed) ((pageCount ?: 1) - 1).coerceAtLeast(0) else 0
                    q.upsertProgress(chapterId, lastPage.toLong(), if (completed) 1 else 0, now, deviceId)
                }
            }
        }

    /** Bulk mark/unmark every chapter of the given series (PLAN.md §7.5 library selection). */
    suspend fun markSeriesProgress(seriesIds: List<String>, completed: Boolean, deviceId: String) = withContext(ioDispatcher) {
        val now = nowEpochMillis()
        q.transaction {
            seriesIds.forEach { seriesId ->
                q.selectChaptersForSeries(seriesId).executeAsList().forEach { c ->
                    val lastPage = if (completed) ((c.page_count ?: 1L) - 1).coerceAtLeast(0) else 0L
                    q.upsertProgress(c.id, lastPage, if (completed) 1 else 0, now, deviceId)
                }
            }
        }
    }

    /**
     * Resolves a cross-device sync key (PLAN.md §10) to the local chapter it corresponds to,
     * or null if this device hasn't scanned that file (yet, or ever). Tries the matched-provider
     * identity first, falling back to the frozen normalized title (case 3 of the design).
     */
    suspend fun resolveLocalChapterId(
        provider: String?,
        externalId: String?,
        normalizedTitle: String,
        volume: Double?,
        number: Double?,
    ): String? = withContext(ioDispatcher) {
        if (provider != null && externalId != null) {
            q.selectChapterIdByProviderKey(provider, externalId, volume, number).executeAsOneOrNull()?.let { return@withContext it }
        }
        q.selectChapterIdByTitleKey(normalizedTitle, volume, number).executeAsOneOrNull()
    }

    /**
     * Applies a synced winner (PLAN.md §10) unless the local row is already at least as new --
     * a stale remote record must never clobber a fresher local write. Done as a Kotlin-side
     * read-then-conditional-write inside one transaction, rather than a SQL `WHERE` clause on
     * `DO UPDATE`: that syntax needs SQLite 3.35+, not guaranteed at this project's minSdk 26
     * (`core/data/build.gradle.kts` pins SQLDelight's dialect to 3.24, and there's no bundled
     * modern-SQLite dependency to guarantee a newer runtime version).
     */
    suspend fun applyProgressIfNewer(chapterId: String, lastPageIndex: Int, completed: Boolean, updatedAt: Long, deviceId: String) =
        withContext(ioDispatcher) {
            q.transaction {
                val currentUpdatedAt = q.selectProgressUpdatedAt(chapterId).executeAsOneOrNull()
                if (currentUpdatedAt == null || updatedAt > currentUpdatedAt) {
                    q.upsertProgress(chapterId, lastPageIndex.toLong(), if (completed) 1 else 0, updatedAt, deviceId)
                }
            }
        }

    /** Every chapter this device has any reading progress for, with its sync identity attached
     * (PLAN.md §10) — the local half of a merge pass. */
    suspend fun allProgressForSync(): List<SyncProgressRow> = withContext(ioDispatcher) {
        q.selectAllProgressForSync().executeAsList().map {
            SyncProgressRow(
                provider = it.metadata_provider,
                externalId = it.external_id,
                normalizedTitle = it.sort_title,
                volume = it.volume,
                number = it.number,
                completed = it.completed == 1L,
                lastPageIndex = it.last_page_index.toInt(),
                updatedAt = it.updated_at,
                deviceId = it.device_id,
            )
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

    /** Same single configured-root row as [saveLocalRoot], but for an SMB share (PLAN.md §6) —
     * [configBlob] is [com.oliver.heyme.mangazuki.SmbConfig]'s pipe-joined host/share/rootPath/username
     * (the password lives in encrypted storage, not here). Switching between LOCAL and SMB
     * reuses the fixed row id, so `type` must actually update on conflict — see `upsertSource`. */
    suspend fun saveSmbSource(configBlob: String, displayName: String) = withContext(ioDispatcher) {
        q.upsertSource(
            id = LOCAL_SOURCE_ID,
            type = "SMB",
            display_name = displayName,
            config_json = configBlob,
            sync_token = null,
        )
    }

    suspend fun savedLocalRoot(): String? = withContext(ioDispatcher) {
        q.selectSourceRoot(LOCAL_SOURCE_ID).executeAsOneOrNull()
    }

    /** "LOCAL" or "SMB" for the single configured root, or null if none configured yet. */
    suspend fun savedSourceType(): String? = withContext(ioDispatcher) {
        q.selectSourceType(LOCAL_SOURCE_ID).executeAsOneOrNull()
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

    /** Library size before a scan's prune step, so [com.oliver.heyme.mangazuki.LibrarySyncer] can sanity-check
     * a suspiciously small result before deleting the rest (PLAN.md §9.2). */
    suspend fun seriesCount(): Long = withContext(ioDispatcher) { q.selectSeriesCount().executeAsOne() }

    /** Whether [seriesId] is already in the library -- lets [com.oliver.heyme.mangazuki.LibrarySyncer] tell a
     * brand-new discovery from a rescan of a series it already knows about. */
    suspend fun seriesExists(seriesId: String): Boolean =
        withContext(ioDispatcher) { q.seriesExists(seriesId).executeAsOne() }

    /** Settings -> Reset library (PLAN.md §7.1): wipes every series/chapter/progress row and the
     * configured source, so the app returns to its pre-first-scan state. Only touches the DB —
     * cached cover/banner files and the image loader's cache live on disk and are cleared by the
     * caller, which owns those filesystem paths. */
    suspend fun resetLibrary() = withContext(ioDispatcher) {
        q.transaction {
            q.deleteAllReadingProgress()
            q.deleteAllChapters()
            q.deleteAllSeries()
            q.deleteAllSources()
        }
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
    suspend fun applyMetadata(seriesId: String, details: RemoteWorkDetails, coverPath: String?, bannerPath: String?) =
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
                status = details.status,
                format = details.format,
                genres = details.genres.joinToString("|"),
                tags = details.tags.joinToString("|"),
                is_adult = if (details.isAdult) 1L else 0L,
                average_score = details.averageScore?.toLong(),
                site_url = details.siteUrl,
                banner_path = bannerPath,
                metadata_provider = details.providerId,
                id = seriesId,
            )
        }

    /** Stamps that enrichment ran and found nothing good enough (PLAN.md §9.2) — the library
     * badge shows "✕" for these instead of "?" (never checked yet). */
    suspend fun markMetadataChecked(seriesId: String) = withContext(ioDispatcher) {
        q.markMetadataChecked(nowEpochMillis(), seriesId)
    }

    /** Promotes a live-extracted cover (an unmatched series' first-chapter-first-page fallback,
     * PLAN.md §9.4) to a permanent `cover_path` once the platform cover fetcher has written it to
     * app-internal storage — the same storage/lookup [applyMetadata]'s downloaded covers use, so
     * it survives Coil's disk cache being cleared. The `cover_path IS NULL` guard in the query
     * means this can never clobber a real matched cover, no matter the call order. */
    suspend fun cacheSeriesCoverIfMissing(seriesId: String, coverPath: String) = withContext(ioDispatcher) {
        q.setCoverPathIfMissing(coverPath, seriesId)
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
        status = r.status,
        format = r.format,
        genres = r.genres.splitList(),
        tags = r.tags.splitList(),
        isAdult = r.is_adult == 1L,
        averageScore = r.average_score?.toInt(),
        siteUrl = r.site_url,
        bannerPath = r.banner_path,
        metadataProvider = r.metadata_provider,
    )

    private fun String?.splitList(): List<String> = this?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
}

package com.oliver.heyme.mangazuki.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.oliver.heyme.mangazuki.core.data.db.MangaDatabase
import com.oliver.heyme.mangazuki.core.domain.Chapter
import com.oliver.heyme.mangazuki.core.domain.ChapterFormat
import com.oliver.heyme.mangazuki.core.domain.Series
import com.oliver.heyme.mangazuki.core.metadata.RemoteWorkDetails
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the real schema + queries against an in-memory SQLite (PLAN.md §14). Proves the
 * UPSERT reconcile (re-scan updates, never duplicates) and the library count aggregation.
 */
class LibraryRepositoryTest {

    private fun newRepo(): Pair<LibraryRepository, MangaDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MangaDatabase.Schema.create(driver)
        val db = createMangaDatabase(driver)
        return LibraryRepository(db) to db
    }

    private fun series(id: String, title: String, scanned: Long) =
        Series(id = id, title = title, sortTitle = title.lowercase(), dateAdded = 1, lastScanned = scanned)

    private fun chapter(id: String, seriesId: String, number: Double) =
        Chapter(
            id = id, seriesId = seriesId, sourceId = "local", locator = "/$id",
            format = ChapterFormat.IMAGE_DIR, displayName = "Ch $number", number = number,
            pageCount = 10, dateAdded = 1,
        )

    @Test
    fun rescan_reconciles_not_duplicates() = runTest {
        val (repo, _) = newRepo()
        val s = series("s1", "Berserk", scanned = 1)
        val chapters = listOf(chapter("c1", "s1", 1.0), chapter("c2", "s1", 2.0))

        repo.persistSeries(s, chapters)
        // Re-scan: same deterministic IDs, later timestamp.
        repo.persistSeries(s.copy(lastScanned = 2), chapters)

        val lib = repo.observeLibrary().first()
        assertEquals(1, lib.size, "one series, not duplicated")
        assertEquals(2, lib[0].chapterCount, "two chapters, not four")
    }

    @Test
    fun rescan_removes_series_and_chapters_no_longer_on_disk() = runTest {
        val (repo, _) = newRepo()
        // Scan 1 (t=1): two series.
        repo.persistSeries(series("a", "A", 1), listOf(chapter("a1", "a", 1.0), chapter("a2", "a", 2.0)))
        repo.persistSeries(series("b", "B", 1), listOf(chapter("b1", "b", 1.0)))
        repo.deleteSeriesNotScannedAt(1)
        assertEquals(2, repo.observeLibrary().first().size)

        // Scan 2 (t=2): B is gone from disk; A lost chapter a2.
        repo.persistSeries(series("a", "A", 2), listOf(chapter("a1", "a", 1.0)))
        repo.deleteSeriesNotScannedAt(2)

        val lib = repo.observeLibrary().first()
        assertEquals(1, lib.size, "B removed (not seen in scan 2)")
        assertEquals("A", lib[0].title)
        assertEquals(1, lib[0].chapterCount, "A's stale chapter a2 pruned")
    }

    @Test
    fun library_counts_unread_from_progress() = runTest {
        val (repo, db) = newRepo()
        repo.persistSeries(series("s1", "X", 1), listOf(chapter("c1", "s1", 1.0), chapter("c2", "s1", 2.0)))

        // Mark one chapter completed directly through the DB (no progress API yet — Phase 2).
        db.schemaQueries.upsertProgress(
            chapter_id = "c1", last_page_index = 0, completed = 1, updated_at = 5, device_id = null,
        )

        val card = repo.observeLibrary().first().single()
        assertEquals(2, card.chapterCount)
        assertEquals(1, card.unreadCount, "one of two chapters read")
    }

    @Test
    fun saved_local_root_round_trips() = runTest {
        val (repo, _) = newRepo()
        assertEquals(null, repo.savedLocalRoot())
        repo.saveLocalRoot("content://tree/primary%3AManga", "Manga")
        assertEquals("content://tree/primary%3AManga", repo.savedLocalRoot())
    }

    @Test
    fun unmatched_series_excludes_ones_with_an_external_id() = runTest {
        val (repo, _) = newRepo()
        repo.persistSeries(series("a", "A", 1), emptyList())
        repo.persistSeries(series("b", "B", 1), emptyList())
        repo.applyMetadata("b", details(), coverPath = null, bannerPath = null)

        assertEquals(listOf("a" to "A"), repo.unmatchedSeries())
    }

    @Test
    fun apply_metadata_round_trips_and_survives_a_rescan() = runTest {
        val (repo, _) = newRepo()
        val s = series("a", "A", 1)
        repo.persistSeries(s, emptyList())
        repo.applyMetadata("a", details(), coverPath = "/covers/a.jpg", bannerPath = "/covers/a_banner.jpg")

        val enriched = repo.observeSeries("a").first()
        assertEquals("Jane Author", enriched?.author)
        assertEquals("A clean description.", enriched?.description)
        assertEquals("/covers/a.jpg", enriched?.coverPath)
        assertEquals(1999, enriched?.startYear)
        assertEquals("42", enriched?.externalId)
        assertEquals("A Romaji", enriched?.titleRomaji)
        assertEquals("A", enriched?.titleEnglish)
        assertEquals("A Native", enriched?.titleNative)
        assertEquals("FINISHED", enriched?.status)
        assertEquals("MANGA", enriched?.format)
        assertEquals(listOf("Comedy", "Drama"), enriched?.genres)
        assertEquals(listOf("Iyashikei"), enriched?.tags)
        assertEquals(true, enriched?.isAdult)
        assertEquals(88, enriched?.averageScore)
        assertEquals("https://anilist.co/manga/42", enriched?.siteUrl)
        assertEquals("/covers/a_banner.jpg", enriched?.bannerPath)
        assertEquals("ANILIST", enriched?.metadataProvider)

        // Rescan (same series, later timestamp) must not clobber the applied metadata —
        // upsertSeries's ON CONFLICT deliberately excludes these columns.
        repo.persistSeries(s.copy(lastScanned = 2), emptyList())
        val afterRescan = repo.observeSeries("a").first()
        assertEquals("Jane Author", afterRescan?.author)
        assertEquals("42", afterRescan?.externalId)
        assertEquals("A Romaji", afterRescan?.titleRomaji)
        assertEquals(listOf("Comedy", "Drama"), afterRescan?.genres)
    }

    @Test
    fun unmatched_series_empty_when_none_pending() = runTest {
        val (repo, _) = newRepo()
        repo.persistSeries(series("a", "A", 1), emptyList())
        repo.applyMetadata("a", details(), coverPath = null, bannerPath = null)
        assertEquals(emptyList(), repo.unmatchedSeries())
    }

    @Test
    fun mark_metadata_checked_shows_up_on_the_library_card_but_series_stays_unmatched() = runTest {
        val (repo, _) = newRepo()
        repo.persistSeries(series("a", "A", 1), emptyList())

        val before = repo.observeLibrary().first().single()
        assertEquals(null, before.externalId)
        assertEquals(null, before.metadataCheckedAt)

        repo.markMetadataChecked("a")

        val after = repo.observeLibrary().first().single()
        assertEquals(null, after.externalId, "still unmatched — checking isn't matching")
        assertEquals(true, after.metadataCheckedAt != null, "but now recorded as checked")
        // A checked-but-unmatched series is still queued for another attempt later.
        assertEquals(listOf("a" to "A"), repo.unmatchedSeries())
    }

    @Test
    fun reset_library_wipes_series_chapters_progress_and_source() = runTest {
        val (repo, db) = newRepo()
        repo.persistSeries(series("a", "A", 1), listOf(chapter("c1", "a", 1.0)))
        repo.applyMetadata("a", details(), coverPath = "/covers/a.jpg", bannerPath = null)
        db.schemaQueries.upsertProgress(chapter_id = "c1", last_page_index = 3, completed = 0, updated_at = 5, device_id = null)
        repo.saveLocalRoot("content://tree/primary%3AManga", "Manga")

        assertEquals(1, repo.observeLibrary().first().size)
        assertEquals("content://tree/primary%3AManga", repo.savedLocalRoot())

        repo.resetLibrary()

        assertEquals(emptyList(), repo.observeLibrary().first(), "no series left")
        assertEquals(emptyList(), repo.observeChapters("a").first(), "no chapters left")
        assertEquals(null, repo.savedLocalRoot(), "source forgotten")
        assertEquals(null, repo.savedSourceType())
        assertEquals(0L, db.schemaQueries.selectSeriesCount().executeAsOne())
    }

    private fun details() = RemoteWorkDetails(
        externalId = "42",
        title = "A",
        titleRomaji = "A Romaji",
        titleEnglish = "A",
        titleNative = "A Native",
        author = "Jane Author",
        description = "A clean description.",
        coverUrl = "https://example.com/a.jpg",
        startYear = 1999,
        status = "FINISHED",
        format = "MANGA",
        genres = listOf("Comedy", "Drama"),
        tags = listOf("Iyashikei"),
        isAdult = true,
        averageScore = 88,
        siteUrl = "https://anilist.co/manga/42",
        bannerUrl = "https://example.com/a_banner.jpg",
        providerId = "ANILIST",
    )
}
